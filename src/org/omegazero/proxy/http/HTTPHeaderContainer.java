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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a collection of HTTP header key-value pairs.
 * 
 * @since 3.4.1
 */
public class HTTPHeaderContainer implements Serializable {

	private static final long serialVersionUID = 1L;

	protected final Map<String, String> headerFields;

	public HTTPHeaderContainer(Map<String, String> trailers) {
		if(trailers == null)
			this.headerFields = new HashMap<>();
		else
			this.headerFields = trailers;
	}


	/**
	 * 
	 * @param key The HTTP field name of this header field
	 * @return The value of this header field, or <code>null</code> if it does not exist
	 */
	public String getHeader(String key) {
		return this.headerFields.get(Objects.requireNonNull(key));
	}

	/**
	 * 
	 * @param key The HTTP field name of this header field
	 * @param def A value to return if a header field with the specified name does not exist
	 * @return The value of this header field, or <b>def</b> if it does not exist
	 */
	public String getHeader(String key, String def) {
		String v = this.getHeader(key);
		if(v == null)
			v = def;
		return v;
	}

	/**
	 * 
	 * @param key   The HTTP field name of this header field
	 * @param value The value of this header field. If <code>null</code>, the header will be deleted
	 */
	public void setHeader(String key, String value) {
		this.checkLocked();
		Objects.requireNonNull(key);
		if(value == null)
			this.headerFields.remove(key);
		else
			this.headerFields.put(key, value);
	}

	/**
	 * Appends the given <b>value</b> to an existing header with the given <b>key</b>, separated by <b>separator</b>, or sets a header with the given <b>value</b> if no such
	 * header exists.
	 * 
	 * @param key       The HTTP field name of this header field
	 * @param value     The value to append to this header field, or the value of the header if it did not exist
	 * @param separator The separator between the existing value and the new value
	 */
	public void appendHeader(String key, String value, String separator) {
		this.checkLocked();
		String val = this.getHeader(key);
		val = ((val != null) ? (val + Objects.requireNonNull(separator)) : "") + Objects.requireNonNull(value);
		this.setHeader(key, val);
	}

	/**
	 * 
	 * @param key The HTTP field name of the header to search for
	 * @return <code>true</code> if a header with the given key exists
	 */
	public boolean headerExists(String key) {
		return this.headerFields.containsKey(key);
	}

	/**
	 * Deletes the header entry with the given key.<br>
	 * <br>
	 * This call is equivalent to <code>setHeader(key, null)</code>.
	 * 
	 * @param key The header to delete
	 */
	public void deleteHeader(String key) {
		this.setHeader(key, null);
	}

	public Set<Map.Entry<String, String>> getHeaderSet() {
		return Collections.unmodifiableSet(this.headerFields.entrySet());
	}


	protected void checkLocked() {
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("HTTPHeaderContainer[").append(this.headerFields.toString()).append("]");
		return sb.toString();
	}
}
