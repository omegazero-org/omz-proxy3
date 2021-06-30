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
package org.omegazero.proxy.util;

public final class ArrayUtil {

	private ArrayUtil() {
	}


	/**
	 * Searches for the sequence of bytes given in the <b>seq</b> in the larger <b>arr</b> byte array.<br>
	 * <br>
	 * An invocation of this method of the form <code>byteArrayIndexOf(arr, seq)</code> is equivalent to
	 * <code>{@link #byteArrayIndexOf(byte[], byte[], int) byteArrayIndexOf}(arr, seq, 0)</code>.
	 * 
	 * @param arr The array in which to search for <b>seq</b>
	 * @param seq The sequence of bytes to search in the larger <b>arr</b> array
	 * @return The index at which the given sequence starts in the <b>arr</b> array, or -1 if the sequence was not found
	 * @see #byteArrayIndexOf(byte[], byte[], int)
	 */
	public static int byteArrayIndexOf(byte[] arr, byte[] seq) {
		return byteArrayIndexOf(arr, seq, 0);
	}

	/**
	 * Searches for the sequence of bytes given in the <b>seq</b> in the larger <b>arr</b> byte array, starting at position <b>start</b>.
	 * 
	 * @param arr   The array in which to search for <b>seq</b>
	 * @param seq   The sequence of bytes to search in the larger <b>arr</b> array
	 * @param start The position to start searching for <b>seq</b>
	 * @return The index at which the given sequence starts in the <b>arr</b> array, or -1 if the sequence was not found
	 */
	public static int byteArrayIndexOf(byte[] arr, byte[] seq, int start) {
		for(int i = start; i < arr.length - seq.length + 1; i++){
			boolean match = true;
			for(int j = 0; j < seq.length; j++){
				if(arr[i + j] != seq[j]){
					match = false;
					if(j > 1)
						i += j - 1;
					break;
				}
			}
			if(match)
				return i;
		}
		return -1;
	}
}
