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

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.config.JSONConfiguration;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.util.SSLUtil;

public class ProxyConfiguration extends JSONConfiguration {

	private static final Logger logger = LoggerUtil.createLogger();

	public static final String TLS_AUTH_DEFAULT_NAME = "default";


	@ConfigurationOption(description = "A list of local addresses the proxy server should bind to")
	private List<InetAddress> bindAddresses = null;
	@ConfigurationOption
	private int backlog = 0;

	@ConfigurationOption(description = "Plaintext ports the proxy server should listen on")
	private List<Integer> portsPlain = new ArrayList<>();
	@ConfigurationOption(description = "TLS ports the proxy server should listen on")
	private List<Integer> portsTls = new ArrayList<>();

	@ConfigurationOption(description = "TLS key and certificate file names for different server names")
	private final Map<String, Entry<String, String>> tlsAuth = new HashMap<>();
	private final Map<String, Entry<PrivateKey, X509Certificate[]>> tlsAuthData = new HashMap<>();

	@ConfigurationOption(description = "The period in seconds for reloading TLS key and certificate data. Disabled if 0")
	private int tlsAuthReloadInterval = 0;

	@ConfigurationOption(description = "The amount of time in seconds a connection with no traffic should persist before it is closed")
	private int connectionIdleTimeout = 300;

	@ConfigurationOption(description = "The default error documents to use to signal an error. The key is the content type, the value is the file path")
	private final Map<String, String> errdocFiles = new HashMap<>();

	@ConfigurationOption(description = "The address of the default upstream server")
	private String upstreamServerAddress = "localhost";
	@ConfigurationOption(description = "The plaintext port of the upstream server")
	private int upstreamServerPortPlain = 80;
	@ConfigurationOption(description = "The TLS port of the upstream server")
	private int upstreamServerPortTLS = 443;
	@ConfigurationOption
	private Set<String> upstreamServerProtocols = null;

	@ConfigurationOption(description = "List of X509 certificate file names to trust in addition to the default installed certificates")
	private List<String> trustedCertificates = new ArrayList<>();

	@ConfigurationOption
	private Map<String, ConfigObject> pluginConfig = new HashMap<>();
	@ConfigurationOption
	private Map<String, ConfigObject> engineConfig = new HashMap<>();
	private Map<Class<?>, HTTPEngineConfig> engineConfigClMap = new HashMap<>();

	@ConfigurationOption
	private ConfigObject defaultEngineConfig = new ConfigObject();


	public ProxyConfiguration(byte[] fileData) {
		super(fileData);
	}

	public ProxyConfiguration(String fileData) {
		super(fileData);
	}


	private void loadConfigurationTLSAuth(JSONObject tlsEntry) {
		String servername;
		if(tlsEntry.has("servername"))
			servername = tlsEntry.getString("servername");
		else
			servername = TLS_AUTH_DEFAULT_NAME;
		if(!tlsEntry.has("key"))
			throw new IllegalArgumentException("Value in 'tlsAuth' is missing required argument 'key'");
		if(!tlsEntry.has("cert"))
			throw new IllegalArgumentException("Value in 'tlsAuth' is missing required argument 'cert'");
		this.tlsAuth.put(servername, new SimpleEntry<>(tlsEntry.getString("key"), tlsEntry.getString("cert")));
	}

	public void reloadTLSAuthData() throws GeneralSecurityException, IOException {
		synchronized(this.tlsAuthData){
			logger.trace("Reloading TLS auth data");
			this.tlsAuthData.clear(); // remove any possible temporary entries in the map
			for(Entry<String, Entry<String, String>> entry : this.tlsAuth.entrySet()){
				this.tlsAuthData.put(entry.getKey(),
						new SimpleEntry<>(SSLUtil.loadPrivateKeyFromPEM(entry.getValue().getKey()), SSLUtil.loadCertificatesFromPEM(entry.getValue().getValue())));
			}
		}
	}


	@Override
	protected boolean setUnsupportedField(Field field, Object jsonObject) {
		if(field.getName().equals("tlsAuth")){
			if(jsonObject instanceof JSONArray){
				((JSONArray) jsonObject).forEach((obj) -> {
					if(obj instanceof JSONObject){
						this.loadConfigurationTLSAuth((JSONObject) obj);
					}else
						throw new IllegalArgumentException("Values in 'tlsAuth' must be objects");
				});
			}else if(jsonObject instanceof JSONObject){
				this.loadConfigurationTLSAuth((JSONObject) jsonObject);
			}else
				throw new IllegalArgumentException("'tlsAuth' must be either an array or object");
			try{
				this.reloadTLSAuthData();
			}catch(GeneralSecurityException | IOException e){
				throw new RuntimeException("Failed to load TLS auth data: ", e);
			}
		}else if(field.getName().equals("errdocFiles")){
			if(jsonObject instanceof JSONObject){
				JSONObject j = ((JSONObject) jsonObject);
				for(String k : j.keySet()){
					this.errdocFiles.put(k, j.getString(k));
				}
			}else
				throw new IllegalArgumentException("'errdocFiles' must be an object");
		}else if(field.getName().equals("upstreamServerProtocols")){
			if(jsonObject instanceof JSONArray){
				this.upstreamServerProtocols = new java.util.HashSet<>();
				((JSONArray) jsonObject).forEach((obj) -> {
					if(obj instanceof String){
						this.upstreamServerProtocols.add((String) obj);
					}else
						throw new IllegalArgumentException("Values in 'upstreamServerProtocols' must be strings");
				});
			}else
				throw new IllegalArgumentException("'upstreamServerProtocols' must be an array");
		}else if(field.getName().equals("pluginConfig")){
			if(jsonObject instanceof JSONObject){
				JSONObject j = ((JSONObject) jsonObject);
				for(String k : j.keySet()){
					this.pluginConfig.put(k, convertJSONObject(j.getJSONObject(k)));
				}
			}else
				throw new IllegalArgumentException("'pluginConfig' must be an object");
		}else if(field.getName().equals("engineConfig")){
			if(jsonObject instanceof JSONObject){
				JSONObject j = ((JSONObject) jsonObject);
				for(String k : j.keySet()){
					this.engineConfig.put(k, convertJSONObject(j.getJSONObject(k)));
				}
			}else
				throw new IllegalArgumentException("'engineConfig' must be an object");
		}else if(field.getName().equals("bindAddresses")){
			// JSONArray check already done because it is a list
			((JSONArray) jsonObject).forEach((obj) -> {
				if(ProxyConfiguration.this.bindAddresses == null)
					ProxyConfiguration.this.bindAddresses = new ArrayList<>();
				if(obj == JSONObject.NULL){
					ProxyConfiguration.this.bindAddresses.add(null);
				}else if(obj instanceof String){
					try{
						ProxyConfiguration.this.bindAddresses.add(InetAddress.getByName((String) obj));
					}catch(UnknownHostException e){
						throw new IllegalArgumentException("Invalid local address '" + obj + "'");
					}
				}else
					throw new IllegalArgumentException("Values in 'bindAddresses' must be strings");
			});
		}else
			return false;
		return true;
	}


	public void validateConfig() {
		if(this.portsTls.size() > 0 && this.tlsAuthData.isEmpty()){
			logger.warn("TLS ports were configured but no valid TLS data (key/certificate) was provided");
		}
	}


	public List<InetAddress> getBindAddresses() {
		return this.bindAddresses;
	}

	public int getBacklog() {
		return this.backlog;
	}

	public List<Integer> getPortsPlain() {
		return this.portsPlain;
	}

	public List<Integer> getPortsTls() {
		return this.portsTls;
	}

	public Map<String, Entry<PrivateKey, X509Certificate[]>> getTlsAuthData() {
		return this.tlsAuthData;
	}

	public int getTlsAuthReloadInterval() {
		return this.tlsAuthReloadInterval;
	}

	public int getConnectionIdleTimeout() {
		return this.connectionIdleTimeout;
	}

	public Map<String, String> getErrdocFiles() {
		return this.errdocFiles;
	}

	public String getUpstreamServerAddress() {
		return this.upstreamServerAddress;
	}

	public int getUpstreamServerPortPlain() {
		return this.upstreamServerPortPlain;
	}

	public int getUpstreamServerPortTLS() {
		return this.upstreamServerPortTLS;
	}

	public Set<String> getUpstreamServerProtocols() {
		return this.upstreamServerProtocols;
	}

	public List<String> getTrustedCertificates() {
		return this.trustedCertificates;
	}

	public ConfigObject getPluginConfigFor(String key) {
		ConfigObject o = this.pluginConfig.get(key);
		if(o != null)
			return o;
		else
			return new ConfigObject();
	}

	public ConfigObject getEngineConfigFor(Class<? extends org.omegazero.proxy.http.HTTPEngine> cl) {
		if(this.engineConfigClMap.containsKey(cl)){
			if(logger.debug())
				logger.trace("Using cached config for engine ", cl.getName());
			return this.engineConfigClMap.get(cl);
		}else{
			if(logger.debug())
				logger.trace("Searching config for engine ", cl.getName(), " using class name");
			ConfigObject o = this.engineConfig.get(cl.getName());
			if(o == null)
				o = this.engineConfig.get(cl.getSimpleName());
			if(o != null)
				o = this.defaultEngineConfig.merge(o);
			else
				o = this.defaultEngineConfig;
			HTTPEngineConfig ec = new HTTPEngineConfig(o);
			this.engineConfigClMap.put(cl, ec);
			return ec;
		}
	}
}
