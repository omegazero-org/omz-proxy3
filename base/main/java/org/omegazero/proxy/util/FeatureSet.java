/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.util;

import java.util.HashSet;

/**
 * A set of strings representing proxy features.
 *
 * @since 3.10.1
 */
public class FeatureSet extends HashSet<String> {

	private static final long serialVersionUID = 1L;

	/**
	 * Adds all features in the given feature string list, separated by commas, to this feature set.
	 *
	 * @param list The list
	 */
	public void addList(String list){
		String[] opts = list.split(",");
		for(String opt : opts)
			this.add(opt);
	}

	/**
	 * Checks whether the given feature string is in this feature set.
	 * <p>
	 * Feature strings may be matched by a wildcard.
	 * For example, if this feature set contains {@code "f1", "f2.*"}, all of the following feature strings would be considered part of this feature set:
	 * {@code "f1", "f2.*", "f2.anything"}.
	 *
	 * @param option The feature string
	 * @return {@code true} if the given feature is in this feature set
	 */
	public boolean containsFeature(String option){
		if(super.contains(option))
			return true;
		int cs;
		while((cs = option.lastIndexOf('.')) > 0){
			option = option.substring(0, cs);
			String fw = option + ".*";
			if(super.contains(fw))
				return true;
		}
		return false;
	}
}
