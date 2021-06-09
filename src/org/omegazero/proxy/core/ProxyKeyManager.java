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
package org.omegazero.proxy.core;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedKeyManager;

import org.omegazero.common.eventbus.EventResult;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.common.util.PropertyUtil;
import org.omegazero.proxy.config.ProxyConfiguration;

class ProxyKeyManager extends X509ExtendedKeyManager {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final int maxSNICacheNameLen = PropertyUtil.getInt("org.omegazero.proxy.sni.maxCacheNameLen", 64);
	private static final int maxSNICacheMappings = PropertyUtil.getInt("org.omegazero.proxy.sni.maxCacheMappings", 4096);


	private final Proxy proxy;

	private final Map<String, String> aliases = new HashMap<String, String>();

	private Map<String, Entry<PrivateKey, X509Certificate[]>> tlsAuthData;

	public ProxyKeyManager(Proxy proxy) {
		this.proxy = proxy;

		this.tlsDataReload();
	}


	public void tlsDataReload() {
		this.aliases.clear();

		// create copy to not modify the original map when adding new entries
		this.tlsAuthData = new HashMap<>(this.proxy.getConfig().getTlsAuthData());
	}


	private Entry<PrivateKey, X509Certificate[]> getTlsAuthEntry(String name) {
		synchronized(this.tlsAuthData){
			return this.tlsAuthData.get(name);
		}
	}

	@SuppressWarnings("unchecked")
	private boolean getExternalEntry(String name, String keyType) {
		EventResult res = this.proxy.dispatchEventRes(ProxyEvents.MISSING_TLS_DATA, name, keyType);
		if(res.getReturnValue() != null){
			Entry<Object, Object> e = (Entry<Object, Object>) res.getReturnValue();
			if(!(e.getKey() instanceof PrivateKey)){
				logger.warn("Entry key must be of type ", PrivateKey.class.getName(), " but received ", getClassName(e.getKey()));
				return false;
			}
			if(!(e.getValue() instanceof X509Certificate[])){
				logger.warn("Entry value must be of type ", X509Certificate[].class.getName(), " but received ", getClassName(e.getValue()));
				return false;
			}
			synchronized(this.tlsAuthData){
				this.tlsAuthData.put(name, (Entry<PrivateKey, X509Certificate[]>) res.getReturnValue());
			}
			return true;
		}else
			return false;
	}

	private static String getClassName(Object o) {
		return o != null ? o.getClass().getName() : "null";
	}


	@Override
	public String[] getClientAliases(String keyType, Principal[] issuers) {
		return null;
	}

	@Override
	public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
		return null;
	}

	@Override
	public String[] getServerAliases(String keyType, Principal[] issuers) {
		return null;
	}

	@Override
	public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
		return null;
	}

	@Override
	public X509Certificate[] getCertificateChain(String alias) {
		Entry<PrivateKey, X509Certificate[]> e = this.getTlsAuthEntry(alias);
		if(e != null)
			return e.getValue();
		else
			return null;
	}

	@Override
	public PrivateKey getPrivateKey(String alias) {
		Entry<PrivateKey, X509Certificate[]> e = this.getTlsAuthEntry(alias);
		if(e != null)
			return e.getKey();
		else
			return null;
	}

	@Override
	public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
		SSLSession session = engine.getHandshakeSession();
		if(!(session instanceof ExtendedSSLSession)){
			logger.debug("session is not of type ", ExtendedSSLSession.class.getName(), " but ", session.getClass().getName());
			return null;
		}
		ExtendedSSLSession esession = (ExtendedSSLSession) session;
		List<SNIServerName> servernames = esession.getRequestedServerNames();

		String available = null;
		String servername = null;
		synchronized(this.tlsAuthData){
			for(SNIServerName s : servernames){
				servername = new String(s.getEncoded());
				if(this.tlsAuthData.containsKey(servername)){
					available = servername;
					break;
				}
				if(this.aliases.containsKey(servername)){
					available = this.aliases.get(servername);
					break;
				}

				// no direct mapping found, try with higher level names (ie select 'example.com' for sni name 'subdomain.example.com' and cache it if found)
				String c = servername;
				int di;
				while((di = c.indexOf('.')) >= 0){
					c = c.substring(di + 1);
					if(this.tlsAuthData.containsKey(c)){
						if(servername.length() < maxSNICacheNameLen && this.aliases.size() < maxSNICacheMappings)
							this.aliases.put(servername, c);
						available = c;
						break;
					}else if(this.getExternalEntry(c, keyType)){
						available = c;
						break;
					}
				}
				if(available != null)
					break;
			}

			// no matching server name, try default
			if(available == null && this.tlsAuthData.containsKey(ProxyConfiguration.TLS_AUTH_DEFAULT_NAME))
				available = ProxyConfiguration.TLS_AUTH_DEFAULT_NAME;

			// check if found entry is correct key type
			if(available != null){
				Entry<PrivateKey, X509Certificate[]> e = this.tlsAuthData.get(available);
				if(!e.getKey().getAlgorithm().equals(keyType))
					available = null;
			}
		}

		logger.trace("SNI: Selected '", available, "' for '", servername, "' (keyType=", keyType, ")");
		return available;
	}
}
