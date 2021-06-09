/*
 * Copyright (C) 2021 omegazero.org
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

import java.util.ArrayList;
import java.util.List;

public class HTTPErrdoc {

	private final String mimeType;

	private List<Object> elements = new ArrayList<>();
	private int len = 0;

	private String servername = null;

	private HTTPErrdoc(String mimeType) {
		this.mimeType = mimeType;
	}


	private void addElement(Object element) {
		this.elements.add(element);
		if(element instanceof String)
			this.len += ((String) element).length();
	}

	public String generate(int status, String title, String message, String requestId, String clientAddress) {
		StringBuilder sb = new StringBuilder(this.len);
		for(Object el : this.elements){
			if(el instanceof Type){
				if(el == Type.STATUS)
					sb.append(status);
				else if(el == Type.TITLE)
					sb.append(title);
				else if(el == Type.MESSAGE)
					sb.append(message);
				else if(el == Type.REQUESTID)
					sb.append(requestId);
				else if(el == Type.CLIENTADDRESS)
					sb.append(clientAddress);
				else if(el == Type.SERVERNAME)
					sb.append(this.servername);
				else if(el == Type.TIME)
					sb.append(HTTPCommon.dateString());
			}else
				sb.append(el);
		}
		return sb.toString();
	}


	public void setServername(String servername) {
		this.servername = servername;
	}

	public String getMimeType() {
		return mimeType;
	}


	public static HTTPErrdoc fromString(String data) {
		return HTTPErrdoc.fromString(data, "text/html");
	}

	/**
	 * Generates a new <code>HTTPErrdoc</code> instance from the given <b>data</b>.<br>
	 * <br>
	 * The <b>mimeType</b> argument is the type returned by {@link HTTPErrdoc#getMimeType()}.
	 * 
	 * @param data     The string data
	 * @param mimeType The MIME type of the error document
	 * @return The new <code>HTTPErrdoc</code> instance
	 */
	public static HTTPErrdoc fromString(String data, String mimeType) {
		HTTPErrdoc errdoc = new HTTPErrdoc(mimeType);
		int lastEnd = 0;
		while(true){
			int startIndex = data.indexOf("${", lastEnd);
			if(startIndex < 0)
				break;
			errdoc.addElement(data.substring(lastEnd, startIndex));
			int endIndex = data.indexOf('}', startIndex);
			if(endIndex < 0)
				throw new RuntimeException("Missing closing '}' at position " + startIndex);
			lastEnd = endIndex + 1;
			String typename = data.substring(startIndex + 2, endIndex);
			Type type = HTTPErrdoc.resolveType(typename);
			if(type == null)
				throw new IllegalArgumentException("Invalid variable name '" + typename + "' at position " + startIndex);
			errdoc.addElement(type);
		}
		errdoc.addElement(data.substring(lastEnd));
		return errdoc;
	}

	private static Type resolveType(String name) {
		for(Type t : Type.values()){
			if(name.toUpperCase().equals(t.toString()))
				return t;
		}
		return null;
	}


	private static enum Type {
		STATUS, TITLE, MESSAGE, REQUESTID, CLIENTADDRESS, SERVERNAME, TIME;
	}
}
