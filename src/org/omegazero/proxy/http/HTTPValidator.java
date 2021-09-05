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

/**
 * Contains several static methods for validating HTTP message components.
 * 
 * @since 3.3.1
 */
public class HTTPValidator {


	/**
	 * 
	 * @param s The string to check
	 * @return <code>true</code> if the given string is non-<code>null</code> and consists solely of 2-10 uppercase letters
	 */
	public static boolean validMethod(String s) {
		if(s == null || s.length() < 2 || s.length() > 10)
			return false;
		for(int i = 0; i < s.length(); i++){
			char c = s.charAt(i);
			if(c < 'A' || c > 'Z')
				return false;
		}
		return true;
	}

	/**
	 * 
	 * @param s The string to check
	 * @return <code>true</code> if the given string is non-<code>null</code> and only consists of printable ASCII characters
	 * @see #validString(String)
	 */
	public static boolean validAuthority(String s) {
		return validString(s);
	}

	/**
	 * 
	 * @param s The string to check
	 * @return <code>true</code> if the given string is non-<code>null</code> and either starts with a slash (<code>'/'</code>) and only contains printable ASCII characters or
	 *         is exactly equal to <code>'*'</code>
	 */
	public static boolean validPath(String s) {
		if(s == null || s.length() < 1 || !(s.charAt(0) == '/' || s.equals("*")))
			return false;
		return validString(s);
	}

	/**
	 * 
	 * @param s The string to check
	 * @return <code>true</code> if the given string is non-<code>null</code> and represents a valid integer in the range 100 to 999
	 * @see #parseStatus(String)
	 */
	public static boolean validStatus(String s) {
		if(s == null || s.length() != 3)
			return false;
		for(int i = 0; i < 3; i++){
			char c = s.charAt(i);
			if(c < '0' || c > '9')
				return false;
		}
		return true;
	}

	/**
	 * 
	 * @param s The string to parse
	 * @return The status number that the given string represents, or <code>-1</code> if the string does not contain a valid status code
	 * @see #validStatus(String)
	 * @see Integer#parseInt(String)
	 */
	public static int parseStatus(String s) {
		if(!validStatus(s))
			return -1;
		return Integer.parseInt(s);
	}


	/**
	 * 
	 * @param s The string to check
	 * @return <code>true</code> if the given string is non-<code>null</code> and only consists of printable ASCII characters
	 */
	public static boolean validString(String s) {
		if(s == null)
			return false;
		for(int i = 0; i < s.length(); i++){
			char c = s.charAt(i);
			if(c <= 32 || c >= 127)
				return false;
		}
		return true;
	}
}
