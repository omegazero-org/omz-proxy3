/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxy.http;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a collection of HTTP header key-value pairs. Each header (key) may have multiple values.<br>
 * <br>
 * This class is not thread-safe.
 * 
 * @since 3.4.1
 */
public class HTTPHeaderContainer implements Serializable {

	private static final long serialVersionUID = 1L;


	protected final Map<String, String[]> headerFields;

	private transient HeaderIterable iterable;

	/**
	 * Creates a new {@link HTTPHeaderContainer} with an empty set of headers.
	 * 
	 * @since 3.5.1
	 */
	public HTTPHeaderContainer() {
		this.headerFields = new HashMap<>();
	}

	/**
	 * Creates a new {@link HTTPHeaderContainer} with the given <b>headers</b>.
	 * 
	 * @param headers The headers, or <code>null</code> to create an empty set of headers
	 * @since 3.5.1
	 */
	public HTTPHeaderContainer(Map<String, String[]> headers) {
		if(headers == null)
			this.headerFields = new HashMap<>();
		else
			this.headerFields = headers;
	}


	/**
	 * Returns the value of a header with the given <b>key</b> (name).
	 * <ul>
	 * <li>If there is no header with the given key, <code>null</code> is returned</li>
	 * <li>If there is a single header with the given key, its value is returned</li>
	 * <li>If there are multiple headers with the given key, the value of the first header is returned</li>
	 * </ul>
	 * A call to this method is equivalent to a call to
	 * 
	 * <pre>
	 * <code>{@link #getHeader(String, int) getHeader}(key, 0)</code>
	 * </pre>
	 * 
	 * @param key The name of the HTTP header field to get
	 * @return The value of the first header with the given name, or <code>null</code> if none exists
	 * @see #getHeader(String, int)
	 * @see #getHeader(String, String)
	 */
	public String getHeader(String key) {
		return this.getHeader(key, 0);
	}

	/**
	 * Returns the value of a header with the given <b>key</b> (name).<br>
	 * <br>
	 * If multiple header fields with the given name exist, the 0-based <b>index</b> specifies which header to retrieve. This value may be negative, in which case it specifies
	 * the offset from the end of the header value list (for example, a value of <code>-1</code> indicates the last header with the given name). This value is ignored if there
	 * are no headers with the given name.
	 * 
	 * @param key   The name of the HTTP header field to get
	 * @param index The index of the header field to get
	 * @return The value of the header with the given name and index, or <code>null</code> if it does not exist
	 * @since 3.5.1
	 * @see #getHeader(String)
	 * @see #getHeader(String, int, String)
	 */
	public String getHeader(String key, int index) {
		String[] h = this.headerFields.get(Objects.requireNonNull(key));
		if(h != null){
			if(index < 0)
				index = h.length + index;
			if(index >= 0 && index < h.length)
				return h[index];
			else
				return null;
		}else
			return null;
	}

	/**
	 * Returns the value of a header with the given <b>key</b> (name), or <b>def</b> if no header with the given name exists. See {@link #getHeader(String)} for more
	 * information.
	 * 
	 * @param key The name of the HTTP header field to get
	 * @param def A value to return if a header field with the specified name does not exist
	 * @return The value of the first header with the given name, or <b>def</b> if none exists
	 * @see #getHeader(String, int, String)
	 * @see #getHeader(String)
	 */
	public String getHeader(String key, String def) {
		String v = this.getHeader(key);
		if(v == null)
			return def;
		else
			return v;
	}

	/**
	 * Returns the value of a header with the given <b>key</b> (name), or <b>def</b> if it does not exist. See {@link #getHeader(String, int)} for more information.
	 * 
	 * @param key   The name of the HTTP header field to get
	 * @param index The index of the header field to get
	 * @param def   A value to return if a header field with the specified name and index does not exist
	 * @return The value of the header with the given name and index, or <b>def</b> if it does not exist
	 * @since 3.5.1
	 * @see #getHeader(String, String)
	 * @see #getHeader(String, int)
	 */
	public String getHeader(String key, int index, String def) {
		String v = this.getHeader(key, index);
		if(v == null)
			return def;
		else
			return v;
	}

	/**
	 * Deletes all headers with the given <b>key</b> (name) and returns the value of the first deleted header.
	 * 
	 * @param key The name of the HTTP header field(s) to delete
	 * @return The previous value of the first header with the given name, or <code>null</code> if it did not exist
	 */
	public String extractHeader(String key) {
		this.checkLocked();
		String[] h = this.headerFields.remove(Objects.requireNonNull(key));
		if(h != null)
			return h[0];
		else
			return null;
	}

	/**
	 * Deletes all headers with the given <b>key</b> (name) and returns the previous header values. The returned array may have length <code>0</code> if no headers with the
	 * given name existed.
	 * 
	 * @param key The name of the HTTP header field(s) to delete
	 * @return The previous value(s) of the header(s) with the given name
	 */
	public String[] extractHeaders(String key) {
		this.checkLocked();
		String[] h = this.headerFields.remove(Objects.requireNonNull(key));
		if(h != null){
			return h;
		}else
			return new String[0];
	}

	/**
	 * Sets the value of the header with the given <b>key</b> (name) to the given <b>value</b>. If present, all previous headers with this name are replaced by a single header
	 * with the given value, or deleted, if the given <b>value</b> is <code>null</code>.
	 * 
	 * @param key   The name of the HTTP header field to set
	 * @param value The value of this header field, replacing any previous headers. If <code>null</code>, the header will be deleted
	 */
	public void setHeader(String key, String value) {
		this.checkLocked();
		Objects.requireNonNull(key);
		if(value == null)
			this.headerFields.remove(key);
		else
			this.headerFields.put(key, new String[] { value });
	}

	/**
	 * Edits an existing header with the given <b>key</b> (name) by setting its value to the given <b>value</b>. The <b>value</b> may be <code>null</code>, in which case the
	 * specified header is deleted.<br>
	 * <br>
	 * If multiple header fields with the given name exist, the 0-based <b>index</b> specifies which header to edit. This value may be negative, in which case it specifies the
	 * offset from the end of the header value list (for example, a value of <code>-1</code> indicates the last header with the given name).
	 * 
	 * @param key   The name of the HTTP header field to edit
	 * @param value The new value of the HTTP header field
	 * @param index The index of the header field to edit
	 * @return The previous value of the header
	 * @throws ArrayIndexOutOfBoundsException If no header with the given name and <b>index</b> exists
	 * @since 3.5.1
	 * @see #addHeader(String, String, int)
	 */
	public String editHeader(String key, String value, int index) {
		this.checkLocked();
		String[] vals = this.headerFields.get(Objects.requireNonNull(key));
		if(vals != null){
			if(index < 0)
				index = vals.length + index;
			if(index < 0 || index >= vals.length)
				throw new ArrayIndexOutOfBoundsException("index: " + index + "  header count: " + vals.length);
			if(value != null){
				String prev = vals[index];
				vals[index] = value;
				return prev;
			}else if(vals.length > 1){
				String[] nv = new String[vals.length - 1];
				System.arraycopy(vals, 0, nv, 0, index);
				System.arraycopy(vals, index + 1, nv, index, vals.length - index - 1);
				this.headerFields.put(key, nv);
				return vals[index];
			}else{
				this.headerFields.remove(key);
				return vals[index];
			}
		}else
			throw new ArrayIndexOutOfBoundsException("index: " + index + "  header count: 0");
	}

	/**
	 * Adds a new header with the given <b>key</b> (name) and <b>value</b> to the end of the header list. This, in contrast to {@link #setHeader(String, String)}, preserves
	 * any existing headers with the given name.
	 * 
	 * @param key   The name of the HTTP header field
	 * @param value The new value of the HTTP header field
	 * @since 3.5.1
	 * @see #addHeader(String, String, int)
	 * @see #editHeader(String, String, int)
	 */
	public void addHeader(String key, String value) {
		this.addHeader(key, value, -1);
	}

	/**
	 * Adds a new header with the given <b>key</b> (name) and <b>value</b> at the given <b>index</b>.<br>
	 * <br>
	 * Any existing header at this index and subsequent headers are moved to the next higher index and other headers are preserved. Unlike other methods in this class, the
	 * only negative value the <b>index</b> parameter may have is <code>-1</code>, in which case the new header is added at the end of the list.
	 * 
	 * @param key   The name of the HTTP header field
	 * @param value The new value of the HTTP header field
	 * @param index The index at which to insert the new header field
	 * @throws ArrayIndexOutOfBoundsException If the index is out of range
	 * @since 3.5.1
	 * @see #addHeader(String, String)
	 * @see #editHeader(String, String, int)
	 */
	public void addHeader(String key, String value, int index) {
		this.checkLocked();
		Objects.requireNonNull(value);
		String[] vals = this.headerFields.get(Objects.requireNonNull(key));
		if(vals != null){
			if(index == -1)
				index = vals.length;
			else if(index < 0 || index > vals.length)
				throw new ArrayIndexOutOfBoundsException("index: " + index + "  header count: " + vals.length);
			String[] nv = new String[vals.length + 1];
			System.arraycopy(vals, 0, nv, 0, index);
			if(index < vals.length)
				System.arraycopy(vals, index, nv, index + 1, vals.length - index);
			nv[index] = value;
			this.headerFields.put(key, nv);
		}else if(index == 0 || index == -1){
			this.headerFields.put(key, new String[] { value });
		}else
			throw new ArrayIndexOutOfBoundsException("index: " + index + "  header count: 0");
	}

	/**
	 * Appends the given <b>value</b> to an existing header with the given <b>key</b> (name), separated by <code>", "</code>, or sets a header with the given <b>value</b> if
	 * no such header exists.<br>
	 * <br>
	 * If multiple headers with the given name exist, only the last one is edited.<br>
	 * <br>
	 * A call to this method is equivalent to a call to
	 * 
	 * <pre>
	 * <code>{@link #appendHeader(String, String, String, int) appendHeader}(key, value, ", ", -1)</code>
	 * </pre>
	 * 
	 * @param key   The name of the HTTP header field to edit
	 * @param value The value to append to this header field, or the value of the header if it did not exist
	 * @since 3.5.1
	 * @see #appendHeader(String, String, String)
	 */
	public void appendHeader(String key, String value) {
		this.appendHeader(key, value, ", ", -1);
	}

	/**
	 * Appends the given <b>value</b> to an existing header with the given <b>key</b> (name), separated by <b>separator</b>, or sets a header with the given <b>value</b> if no
	 * such header exists.<br>
	 * <br>
	 * If multiple headers with the given name exist, only the last one is edited.<br>
	 * <br>
	 * A call to this method is equivalent to a call to
	 * 
	 * <pre>
	 * <code>{@link #appendHeader(String, String, String, int) appendHeader}(key, value, separator, -1)</code>
	 * </pre>
	 * 
	 * @param key       The name of the HTTP header field to edit
	 * @param value     The value to append to this header field, or the value of the header if it did not exist
	 * @param separator The separator between the existing value and the new value
	 * @see #appendHeader(String, String)
	 */
	public void appendHeader(String key, String value, String separator) {
		this.appendHeader(key, value, separator, -1);
	}

	/**
	 * Appends the given <b>value</b> to an existing header with the given <b>key</b> (name), separated by <b>separator</b>, or sets a header with the given <b>value</b> if no
	 * such header exists.<br>
	 * <br>
	 * If multiple header fields with the given name exist, the 0-based <b>index</b> specifies which header to edit. This value may be negative, in which case it specifies the
	 * offset from the end of the header value list (for example, a value of <code>-1</code> indicates the last header with the given name). This value is ignored if there are
	 * no headers with the given name.
	 * 
	 * @param key       The name of the HTTP header field to edit
	 * @param value     The value to append to this header field, or the value of the header if it did not exist
	 * @param separator The separator between the existing value and the new value
	 * @param index     The index of the header field to edit
	 * @throws ArrayIndexOutOfBoundsException If there exists at least one header with the given name and <b>index</b> is out of range
	 * @since 3.5.1
	 * @see #appendHeader(String, String, String)
	 */
	public void appendHeader(String key, String value, String separator, int index) {
		this.checkLocked();
		String[] vals = this.headerFields.get(Objects.requireNonNull(key));
		if(vals != null){
			if(index < 0)
				index = vals.length + index;
			if(index < 0 || index >= vals.length)
				throw new ArrayIndexOutOfBoundsException("index: " + index + "  header count: " + vals.length);
			vals[index] = vals[index] + Objects.requireNonNull(separator) + Objects.requireNonNull(value);
		}else
			this.setHeader(key, Objects.requireNonNull(value));
	}

	/**
	 * Returns the number of header fields with the given <b>key</b> (name).
	 * 
	 * @param key The name of the HTTP header field to search for
	 * @return The number of header fields with the given name
	 * @since 3.5.1
	 */
	public int getHeaderCount(String key) {
		String[] h = this.headerFields.get(Objects.requireNonNull(key));
		if(h != null)
			return h.length;
		else
			return 0;
	}

	/**
	 * Checks whether there is at least one header with the given <b>key</b> (name).
	 * 
	 * @param key The name of the HTTP header field to search for
	 * @return <code>true</code> if a header with the given name exists
	 */
	public boolean headerExists(String key) {
		return this.headerFields.containsKey(key);
	}

	/**
	 * Deletes all headers with the given <b>key</b> (name).<br>
	 * <br>
	 * A call to this method is equivalent to a call to
	 * 
	 * <pre>
	 * <code>{@link #setHeader(String, String) setHeader}(key, null)</code>
	 * </pre>
	 * 
	 * @param key The name of the HTTP header field to delete
	 */
	public void deleteHeader(String key) {
		this.setHeader(key, null);
	}

	/**
	 * Deletes the header with the given <b>key</b> (name) and <b>index</b>.<br>
	 * <br>
	 * A call to this method is equivalent to a call to
	 * 
	 * <pre>
	 * <code>{@link #editHeader(String, String, int) editHeader}(key, null, index)</code>
	 * </pre>
	 * 
	 * @param key   The name of the HTTP header field to delete
	 * @param index The index of the header field to delete
	 * @return The previous value of the header
	 * @since 3.5.1
	 */
	public String deleteHeader(String key, int index) {
		return this.editHeader(key, null, index);
	}

	/**
	 * Returns an unmodifiable set of all headers stored in this {@link HTTPHeaderContainer}. Multiple entries may have the same key if there are multiple header fields with
	 * the same name.
	 * 
	 * @return An unmodifiable set of all headers
	 * @deprecated Since 3.5.1, use {@link #headerSet()} or {@link #headerIterator()} instead.
	 */
	@Deprecated
	public Set<Map.Entry<String, String>> getHeaderSet() {
		Iterator<Map.Entry<String, String>> it = this.headerIterator();
		Set<Map.Entry<String, String>> set = new java.util.HashSet<>(this.headerFields.size());
		while(it.hasNext())
			set.add(it.next());
		return Collections.unmodifiableSet(set);
	}

	/**
	 * Returns an unmodifiable set of all header names stored in this {@link HTTPHeaderContainer}.
	 * 
	 * @return An unmodifiable set of all header names
	 * @since 3.5.1
	 */
	public Set<String> headerNameSet() {
		return Collections.unmodifiableSet(this.headerFields.keySet());
	}

	/**
	 * Returns the number of unique header names. This may not be the actual number of headers if there are multiple headers with the same name.
	 * 
	 * @return The number of unique header names
	 * @since 3.5.1
	 */
	public int headerNameCount() {
		return this.headerFields.size();
	}

	/**
	 * Returns an unmodifiable set of all headers stored in this {@link HTTPHeaderContainer}.
	 * 
	 * @return An unmodifiable set of all headers
	 * @since 3.5.1
	 */
	public Set<Map.Entry<String, String[]>> headerSet() {
		return Collections.unmodifiableSet(this.headerFields.entrySet());
	}

	/**
	 * Returns an iterator iterating over each header key-value pair. The same keys may occur multiple times if multiple headers with the same name exist.
	 * 
	 * @return The iterator
	 * @since 3.5.1
	 * @see #headers()
	 */
	public Iterator<Map.Entry<String, String>> headerIterator() {
		return new HeaderIterator();
	}

	/**
	 * Returns an <code>Iterable</code> for use in a for-each loop. The underlying iterator iterates over each header key-value pair. The same keys may occur multiple times if
	 * multiple headers with the same name exist.
	 * 
	 * @return The <code>Iterable</code>
	 * @since 3.5.1
	 * @see #headerIterator()
	 */
	public Iterable<Map.Entry<String, String>> headers() {
		if(this.iterable == null)
			return this.iterable = new HeaderIterable();
		else
			return this.iterable;
	}


	/**
	 * May be implemented by subclasses to prevent changing header data in certain situations. This method is called each time an action that would change any header data is
	 * about to be performed. To deny this action, this method should throw an <code>IllegalStateException</code>.
	 */
	protected void checkLocked() {
	}


	/**
	 * Returns a string representation of this {@link HTTPHeaderContainer}, containing all header key-value pairs.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("HTTPHeaderContainer[");
		boolean f = true;
		for(Map.Entry<String, String> header : this.headers()){
			if(f)
				f = false;
			else
				sb.append(", ");
			sb.append(header.getKey()).append('=').append(header.getValue());
		}
		sb.append("]");
		return sb.toString();
	}


	/**
	 * Creates a {@link HTTPHeaderContainer} from the given single-valued header list.
	 * 
	 * @return The header data
	 * @since 3.5.1
	 */
	public static HTTPHeaderContainer fromLegacy(Map<String, String> headerData) {
		Map<String, String[]> ndata = new HashMap<>();
		for(Map.Entry<String, String> entry : headerData.entrySet())
			ndata.put(entry.getKey(), new String[] { entry.getValue() });
		return new HTTPHeaderContainer(ndata);
	}


	private class HeaderIterable implements Iterable<Map.Entry<String, String>> {

		@Override
		public Iterator<Map.Entry<String, String>> iterator() {
			return new HeaderIterator();
		}
	}

	private class HeaderIterator implements Iterator<Map.Entry<String, String>> {

		private final Iterator<Map.Entry<String, String[]>> parentIt = HTTPHeaderContainer.this.headerFields.entrySet().iterator();

		private Map.Entry<String, String[]> current;
		private int nextIndex = 0;


		@Override
		public boolean hasNext() {
			return (this.current != null && this.nextIndex < this.current.getValue().length) || this.parentIt.hasNext();
		}

		@Override
		public Map.Entry<String, String> next() {
			if(this.current == null || this.nextIndex >= this.current.getValue().length){
				this.current = this.parentIt.next();
				this.nextIndex = 0;
			}
			return new java.util.AbstractMap.SimpleEntry<String, String>(this.current.getKey(), this.current.getValue()[this.nextIndex++]);
		}
	}
}
