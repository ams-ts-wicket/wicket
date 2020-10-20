/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wicket.markup.transformer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.wicket.Component;
import org.apache.wicket.Page;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.behavior.Behavior;
import org.apache.wicket.protocol.http.BufferedWebResponse;
import org.apache.wicket.request.Response;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.http.WebResponse;

/**
 * A {@link Behavior} which can be added to any component, allowing to post-process (transform) the
 * markup generated by the component.
 * <p>
 * There's one important limitation with the current implementation: Multiple different instances of
 * this behavior CAN NOT be assigned to the same component! If one wishes to do so, the contained
 * container needs to be used to wrap existing behaviors and that container needs to be added to the
 * component instead. The current implementation works with temporary responses, but doesn't support
 * nesting itself properly, which results in missing rendered output and most likely broken HTML
 * documents in the end.
 * </p>
 * @see org.apache.wicket.markup.transformer.AbstractOutputTransformerContainer
 *
 * @author Juergen Donnerstag
 */
public abstract class AbstractTransformerBehavior extends Behavior implements ITransformer
{
	private static final long serialVersionUID = 1L;

	/**
	 * Container to apply multiple {@link AbstractTransformerBehavior} to some component.
	 * <p>
	 * This container is by design NOT about multiple arbitrary transformations, but really only for
	 * the one use case supporting multiple instances of {@link AbstractTransformerBehavior} on one
	 * and the same component. The current implementation of that makes use of temporary responses,
	 * but doesn't support nesting itself properly in case multiple behaviors assigned to the same
	 * component. That results in missing rendered output and most likely entirely broken HTML.
	 * </p>
	 * <p>
	 * The easiest workaround for that problem is simply introducing this container which users need
	 * to use in those cases: An instance needs to be created with all transformers of interest in
	 * the order they should be applied and the container takes care of doing so. As the container
	 * is an {@link AbstractTransformerBehavior} itself, things simply work like with individual
	 * behaviors, while response handling is only managed by the container. So when used with this
	 * container, the callbacks of the internally maintained instances (like
	 * {@link AbstractTransformerBehavior#afterRender(Component)} etc.) are NOT used anymore! OTOH,
	 * the individual behaviors stay useful without the container as well.
	 * </p>
	 * @see <a href="https://issues.apache.org/jira/projects/WICKET/issues/WICKET-6823">JIRA issue</a>
	 */
	public static class Multi extends AbstractTransformerBehavior
	{
		private static final long serialVersionUID = 1L;

		/**
		 * All transformers which need to be applied in the order given by the user, which is the
		 * same order as processed by the container in the end.
		 */
		private final List<AbstractTransformerBehavior> transformers;

		/**
		 * Constructor simply storing the given transformers.
		 *
		 * @param transformers, which must not be {@code null} or empty. Wouldn't makes sense here.
		 */
		private Multi(List<AbstractTransformerBehavior> transformers)
		{
			if ((transformers == null) || transformers.isEmpty())
			{
				throw new IllegalArgumentException("No transformers given.");
			}

			this.transformers = transformers;
		}

		@Override
		public CharSequence transform(Component component, CharSequence output) throws Exception
		{
			CharSequence retVal = output;
			for (AbstractTransformerBehavior trans : this.transformers)
			{
				retVal = trans.transform(component, retVal);
			}

			return retVal;
		}

		/**
		 * Create a new container with the given transformers and with keeping their order.
		 * <p>
		 * This factory expects multiple individual transformers already, as creating a container
		 * with less of those doesn't make too much sense and users should reconsider then if this
		 * container is of use at all. In most cases users do have individual transformers to apply
		 * only anyway and don't need to provide a collection themself this way. OTOH, a collection
		 * could be empty, contain only one element etc. and would then defeat the purpose of this
		 * container again.
		 * </p>
		 * @param first First transformer to apply.
		 * @param second Second transformer to apply.
		 * @param moreIf All other transformers to apply, if at all, in given order.
		 * @return A container with multiple transformers being applied.
		 */
		public static Multi of(	AbstractTransformerBehavior		first,
								AbstractTransformerBehavior		second,
								AbstractTransformerBehavior...	moreIf)
		{
			List<AbstractTransformerBehavior> transformers = new ArrayList<>();

			transformers.add(Objects.requireNonNull(first,	"No first transformer given."));
			transformers.add(Objects.requireNonNull(second,	"No second transformer given."));

			if ((moreIf != null) && (moreIf.length > 0))
			{
				transformers.addAll(Arrays.asList(moreIf));
			}

			return new Multi(transformers);
		}
	}

	/**
	 * The request cycle's response before the transformation.
	 */
	private transient Response originalResponse;

	/**
	 * Create a new response object which is used to store the markup generated by the child
	 * objects.
	 *
	 * @param originalResponse
	 *            the original web response or {@code null} if it isn't a {@link WebResponse}
	 *
	 * @return Response object. Must not be null
	 */
	protected BufferedWebResponse newResponse(final WebResponse originalResponse)
	{
		return new BufferedWebResponse(originalResponse);
	}

	@Override
	public void beforeRender(Component component)
	{
		super.beforeRender(component);

		final RequestCycle requestCycle = RequestCycle.get();

		// Temporarily replace the web response with a String response
		originalResponse = requestCycle.getResponse();

		WebResponse origResponse = (WebResponse)((originalResponse instanceof WebResponse)
			? originalResponse : null);
		BufferedWebResponse tempResponse = newResponse(origResponse);

		// temporarily set StringResponse to collect the transformed output
		requestCycle.setResponse(tempResponse);
	}

	@Override
	public void afterRender(final Component component)
	{
		final RequestCycle requestCycle = RequestCycle.get();

		try
		{
			BufferedWebResponse tempResponse = (BufferedWebResponse)requestCycle.getResponse();

			if (component instanceof Page && originalResponse instanceof WebResponse)
			{
				tempResponse.writeMetaData((WebResponse) originalResponse);
			}

			// Transform the data
			CharSequence output = transform(component, tempResponse.getText());
			originalResponse.write(output);
		}
		catch (Exception ex)
		{
			throw new WicketRuntimeException("Error while transforming the output of component: " +
				component, ex);
		}
		finally
		{
			// Restore the original response object
			requestCycle.setResponse(originalResponse);
		}
	}

	@Override
	public void detach(Component component)
	{
		originalResponse = null;
		super.detach(component);
	}

	@Override
	public abstract CharSequence transform(final Component component, final CharSequence output)
		throws Exception;
}
