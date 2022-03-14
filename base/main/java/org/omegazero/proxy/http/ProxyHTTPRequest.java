package org.omegazero.proxy.http;

import org.omegazero.http.common.HTTPHeaderContainer;
import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPRequest;

/**
 * A {@link HTTPRequest} that stores the initial request line values and contains additional proxy-specific methods.
 * <p>
 * The {@code getInitial*} methods in this class are not affected by their corresponding {@code set*} methods.
 * 
 * @since 3.6.1
 */
public class ProxyHTTPRequest extends HTTPRequest {

	private static final long serialVersionUID = 1L;


	private final String initialMethod;
	private final String initialScheme;
	private final String initialAuthority;
	private final String initialPath;
	private final String initialHttpVersion;

	/**
	 * See {@link HTTPRequest#HTTPRequest(String, String, String, String, String, HTTPHeaderContainer)}.
	 * 
	 * @param method The request method
	 * @param scheme The request URL scheme
	 * @param authority The request URL authority
	 * @param path The request URL path component
	 * @param version The HTTP version
	 * @param headers The HTTP headers
	 */
	public ProxyHTTPRequest(String method, String scheme, String authority, String path, String version, HTTPHeaderContainer headers) {
		super(method, scheme, authority, path, version, headers);
		this.initialMethod = method;
		this.initialScheme = scheme;
		this.initialAuthority = authority;
		this.initialPath = path;
		this.initialHttpVersion = version;
	}

	/**
	 * See {@link HTTPRequest#HTTPRequest(HTTPRequest)}.
	 * 
	 * @param request The {@code ProxyHTTPRequest} to copy from
	 */
	public ProxyHTTPRequest(ProxyHTTPRequest request) {
		super(request);
		this.initialMethod = request.initialMethod;
		this.initialScheme = request.initialScheme;
		this.initialAuthority = request.initialAuthority;
		this.initialPath = request.initialPath;
		this.initialHttpVersion = request.initialHttpVersion;
	}


	/**
	 * Responds to this {@code HTTPRequest} with an error message.
	 * 
	 * @param status The status code of the response
	 * @param title Title of the error message
	 * @param message Error message
	 * @param headers Headers to send in the response
	 * @see HTTPEngine#respondError(HTTPMessage, int, String, String, String...)
	 */
	public void respondError(int status, String title, String message, String... headers) {
		((HTTPEngine) super.httpResponder).respondError(this, status, title, message, headers);
	}

	/**
	 * Returns the request ID of this {@link ProxyHTTPRequest}.
	 * 
	 * @return The request ID
	 */
	public String getRequestId() {
		return (String) super.getAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID);
	}


	/**
	 * Returns the initial HTTP request method. See {@link #getMethod()}.
	 * 
	 * @return The initial HTTP request method
	 */
	public String getInitialMethod() {
		return this.initialMethod;
	}

	/**
	 * Returns the initial HTTP request scheme. See {@link #getScheme()}.
	 * 
	 * @return The initial HTTP request scheme
	 */
	public String getInitialScheme() {
		return this.initialScheme;
	}

	/**
	 * Returns the initial HTTP request authority. See {@link #getAuthority()}.
	 * 
	 * @return The initial HTTP request authority
	 */
	public String getInitialAuthority() {
		return this.initialAuthority;
	}

	/**
	 * Returns the initial HTTP request path. See {@link #getPath()}.
	 * 
	 * @return The initial HTTP request path
	 */
	public String getInitialPath() {
		return this.initialPath;
	}

	/**
	 * Returns the initial HTTP version. See {@link #getHttpVersion()}.
	 * 
	 * @return The initial HTTP version
	 */
	public String getInitialHttpVersion() {
		return this.initialHttpVersion;
	}
}
