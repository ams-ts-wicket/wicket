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
package org.apache.wicket.request.resource;

import java.io.IOException;
import java.util.Locale;

import javax.servlet.http.HttpServletResponse;

import org.apache.wicket.ThreadContext;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.markup.html.IPackageResourceGuard;
import org.apache.wicket.util.io.IOUtils;
import org.apache.wicket.util.lang.Packages;
import org.apache.wicket.util.lang.WicketObjects;
import org.apache.wicket.util.resource.IResourceStream;
import org.apache.wicket.util.resource.ResourceStreamNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PackageResource extends AbstractResource
{
	private static final Logger log = LoggerFactory.getLogger(PackageResource.class);

	private static final long serialVersionUID = 1L;

	/**
	 * Exception thrown when the creation of a package resource is not allowed.
	 */
	public static final class PackageResourceBlockedException extends WicketRuntimeException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Construct.
		 *
		 * @param message
		 */
		public PackageResourceBlockedException(String message)
		{
			super(message);
		}
	}

	/**
	 * The path to the resource
	 */
	private final String absolutePath;

	/**
	 * The resource's locale
	 */
	private final Locale locale;

	/**
	 * The path this resource was created with.
	 */
	private final String path;

	/**
	 * The scoping class, used for class loading and to determine the package.
	 */
	private final String scopeName;

	/**
	 * The resource's style
	 */
	private final String style;

	/**
	 * The component's variation (of the style)
	 */
	private final String variation;


	/**
	 * should response be cacheable in browser?
	 */
	private boolean cacheable = true;

	/**
	 * Hidden constructor.
	 *
	 * @param scope     This argument will be used to get the class loader for loading the package
	 *                  resource, and to determine what package it is in
	 * @param name      The relative path to the resource
	 * @param locale    The locale of the resource
	 * @param style     The style of the resource
	 * @param variation The component's variation (of the style)
	 */
	protected PackageResource(final Class<?> scope, final String name, final Locale locale,
	                          final String style, final String variation)
	{
		// Convert resource path to absolute path relative to base package
		absolutePath = Packages.absolutePath(scope, name);

		if (!accept(scope, name))
		{
			throw new PackageResourceBlockedException(
					"Access denied to (static) package resource " + absolutePath +
							". See IPackageResourceGuard");
		}

		// TODO NG: Check path for ../

		scopeName = scope.getName();
		path = name;
		this.locale = locale;
		this.style = style;
		this.variation = variation;
	}

	/**
	 * Gets the scoping class, used for class loading and to determine the package.
	 *
	 * @return the scoping class
	 */
	public final Class<?> getScope()
	{
		return WicketObjects.resolveClass(scopeName);
	}

	/**
	 * Gets the style.
	 *
	 * @return the style
	 */
	public final String getStyle()
	{
		return style;
	}

	/**
	 * returns is resource is cacheable
	 *
	 * @return <code>true</code> if cacheable
	 */
	public boolean isCacheable()
	{
		return cacheable;
	}

	/**
	 * sets is resource is cacheable
	 *
	 * @param cacheable <code>true</code> if cacheable
	 */
	public void setCacheable(boolean cacheable)
	{
		this.cacheable = cacheable;
	}

	/**
	 * creates a new resource response based on the request attributes
	 *
	 * @param attributes current request attributes from client
	 * @return resource response for answering request
	 */
	@Override
	protected ResourceResponse newResourceResponse(Attributes attributes)
	{
		ResourceResponse resourceResponse = new ResourceResponse();

		if (resourceResponse.dataNeedsToBeWritten(attributes))
		{
			IResourceStream resourceStream = getResourceStream();
			resourceResponse.setContentType(resourceStream.getContentType());

			if (resourceStream == null)
				return sendResourceError(resourceResponse, HttpServletResponse.SC_NOT_FOUND, "Unable to find resource");

			try
			{
				final byte[] bytes;

				try
				{
					bytes = IOUtils.toByteArray(resourceStream.getInputStream());
				}
				finally
				{
					resourceStream.close();
				}
				resourceResponse.setContentLength(bytes.length);
				resourceResponse.setWriteCallback(new WriteCallback()
				{
					@Override
					public void writeData(Attributes attributes)
					{
						attributes.getResponse().write(bytes);
					}
				});
			}
			catch (IOException e)
			{
				log.debug(e.getMessage(), e);
				return sendResourceError(resourceResponse, 500, "Unable to read resource stream");
			}
			catch (ResourceStreamNotFoundException e)
			{
				log.debug(e.getMessage(), e);
				return sendResourceError(resourceResponse, 500, "Unable to open resource stream");
			}
		}
		resourceResponse.setCacheable(isCacheable());
		return resourceResponse;
	}

	/**
	 * send resource specific error message and write log entry
	 *
	 * @param resourceResponse resource response for method chaining
	 * @param errorCode error code (=http status)
	 * @param errorMessage error message (=http error message)
	 * @return
	 */
	private ResourceResponse sendResourceError(ResourceResponse resourceResponse, int errorCode, String errorMessage)
	{
		String msg = String.format("resource [path = %s, style = %s, variation = %s, locale = %s]: %s (status=%d)",
		                           absolutePath, style, variation, locale, errorMessage, errorCode);

		log.warn(msg);

		resourceResponse.setError(errorCode, errorMessage);
		return resourceResponse;
	}

	/**
	 * locate resource stream for current resource
	 *
	 * @return resource stream or <code>null</code> if not found
	 */
	private IResourceStream getResourceStream()
	{
		// Locate resource
		return ThreadContext.getApplication()
				.getResourceSettings()
				.getResourceStreamLocator()
				.locate(getScope(), absolutePath, style, variation, locale, null);
	}

	/**
	 * @param scope
	 * @param path
	 * @return
	 */
	private boolean accept(Class<?> scope, String path)
	{
		IPackageResourceGuard guard = ThreadContext.getApplication()
				.getResourceSettings()
				.getPackageResourceGuard();

		return guard.accept(scope, path);
	}

	/**
	 * Gets whether a resource for a given set of criteria exists.
	 *
	 * @param scope     This argument will be used to get the class loader for loading the package
	 *                  resource, and to determine what package it is in. Typically this is the class in
	 *                  which you call this method
	 * @param path      The path to the resource
	 * @param locale    The locale of the resource
	 * @param style     The style of the resource (see {@link org.apache.wicket.Session})
	 * @param variation The component's variation (of the style)
	 * @return true if a resource could be loaded, false otherwise
	 */
	public static boolean exists(final Class<?> scope, final String path, final Locale locale,
	                             final String style, final String variation)
	{
		String absolutePath = Packages.absolutePath(scope, path);
		return ThreadContext.getApplication()
				.getResourceSettings()
				.getResourceStreamLocator()
				.locate(scope, absolutePath, style, variation, locale, null) != null;
	}
}
