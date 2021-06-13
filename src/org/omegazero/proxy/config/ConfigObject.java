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
package org.omegazero.proxy.config;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

public class ConfigObject implements Serializable {

	private static final long serialVersionUID = 1L;


	protected final HashMap<String, Object> data = new HashMap<>();

	protected ConfigObject() {
	}


	public int size() {
		return this.data.size();
	}

	public boolean isEmpty() {
		return this.data.isEmpty();
	}

	public boolean containsKey(Object key) {
		return this.data.containsKey(key);
	}

	public boolean containsValue(Object value) {
		return this.data.containsValue(value);
	}

	public Set<String> keySet() {
		return Collections.unmodifiableSet(this.data.keySet());
	}

	public Collection<Object> values() {
		return Collections.unmodifiableCollection(this.data.values());
	}


	public Object get(String key) {
		return this.data.get(key);
	}


	public ConfigObject getObject(String key) {
		Object v = this.get(key);
		if(v instanceof ConfigObject)
			return (ConfigObject) v;
		else
			throw new IllegalArgumentException("Expected object for '" + key + "' but received type " + getTypeName(v));
	}

	public ConfigArray getArray(String key) {
		Object v = this.get(key);
		if(v instanceof ConfigArray)
			return (ConfigArray) v;
		else
			throw new IllegalArgumentException("Expected array for '" + key + "' but received type " + getTypeName(v));
	}

	public String getString(String key) {
		Object v = this.get(key);
		if(v instanceof String)
			return (String) v;
		else
			throw new IllegalArgumentException("Expected string for '" + key + "' but received type " + getTypeName(v));
	}

	public int getInt(String key) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).intValue();
		else
			throw new IllegalArgumentException("Expected integer for '" + key + "' but received type " + getTypeName(v));
	}

	public long getLong(String key) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).longValue();
		else
			throw new IllegalArgumentException("Expected integer for '" + key + "' but received type " + getTypeName(v));
	}

	public float getFloat(String key) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).floatValue();
		else
			throw new IllegalArgumentException("Expected floating point value for '" + key + "' but received type " + getTypeName(v));
	}

	public double getDouble(String key) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).doubleValue();
		else
			throw new IllegalArgumentException("Expected floating point value for '" + key + "' but received type " + getTypeName(v));
	}

	public boolean getBoolean(String key) {
		Object v = this.get(key);
		if(v instanceof Boolean)
			return (boolean) v;
		else
			throw new IllegalArgumentException("Expected boolean for '" + key + "' but received type " + getTypeName(v));
	}


	public ConfigObject optObject(String key) {
		Object v = this.get(key);
		if(v instanceof ConfigObject)
			return (ConfigObject) v;
		else
			return null;
	}

	public ConfigArray optArray(String key) {
		Object v = this.get(key);
		if(v instanceof ConfigArray)
			return (ConfigArray) v;
		else
			return null;
	}

	public String optString(String key, String def) {
		Object v = this.get(key);
		if(v instanceof String)
			return (String) v;
		else
			return def;
	}

	public int optInt(String key, int def) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).intValue();
		else
			return def;
	}

	public long optLong(String key, long def) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).longValue();
		else
			return def;
	}

	public float optFloat(String key, float def) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).floatValue();
		else
			return def;
	}

	public double optDouble(String key, double def) {
		Object v = this.get(key);
		if(v instanceof Number)
			return ((Number) v).doubleValue();
		else
			return def;
	}

	public boolean optBoolean(String key, boolean def) {
		Object v = this.get(key);
		if(v instanceof Boolean)
			return (boolean) v;
		else
			return def;
	}


	private static String getTypeName(Object obj) {
		return obj == null ? "null" : obj.getClass().getName();
	}
}
