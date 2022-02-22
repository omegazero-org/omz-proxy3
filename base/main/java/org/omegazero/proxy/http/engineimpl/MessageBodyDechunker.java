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
package org.omegazero.proxy.http.engineimpl;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

import org.omegazero.common.util.ArrayUtil;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.http.InvalidHTTPMessageException;

public class MessageBodyDechunker {

	private static final byte[] EOL = new byte[] { 0xd, 0xa };


	private final Consumer<byte[]> onData;
	private final long totalSize;
	private final byte[] chunkBuffer;
	private int chunkBufferIndex = 0;
	private long receivedData = 0;
	private boolean ended = false;
	private int lastChunkRemaining = 0;

	public MessageBodyDechunker(HTTPMessage msg, Consumer<byte[]> onData) throws IOException {
		this(msg, onData, 16384);
	}

	public MessageBodyDechunker(HTTPMessage msg, Consumer<byte[]> onData, int chunkBufferSize) throws IOException {
		if(chunkBufferSize <= 0)
			throw new IllegalArgumentException("chunkBufferSize must be positive");
		this.onData = onData;

		boolean request = msg.isRequest();
		HTTPMessage requestMsg = request ? msg : msg.getCorrespondingMessage();
		String transferEncoding = msg.getHeader("transfer-encoding");
		String contentLength = msg.getHeader("content-length");
		if(!request && !HTTPCommon.hasResponseBody(requestMsg, msg)){
			this.totalSize = 0;
			this.chunkBuffer = null;
		}else if("chunked".equals(transferEncoding)){
			this.totalSize = -1;
			this.chunkBuffer = new byte[chunkBufferSize];
		}else if(transferEncoding != null){
			throw new UnsupportedOperationException("Unsupported transfer encoding: " + transferEncoding);
		}else if(contentLength != null){
			long ts;
			try{
				ts = Long.parseLong(contentLength);
			}catch(NumberFormatException e){
				throw new InvalidHTTPMessageException("Invalid Content-Length header value");
			}
			if(ts < 0)
				throw new InvalidHTTPMessageException("Content-Length is negative");
			this.totalSize = ts;
			this.chunkBuffer = null;
		}else if(request){
			this.totalSize = 0;
			this.chunkBuffer = null;
		}else{
			this.totalSize = -1;
			this.chunkBuffer = null;
		}
	}


	public void addData(byte[] data) throws IOException {
		if(this.chunkBuffer != null){
			int index = 0;
			while(index < data.length){
				if(this.lastChunkRemaining == 0){
					int chunkHeaderEnd = ArrayUtil.byteArrayIndexOf(data, EOL, index);
					if(chunkHeaderEnd < 0)
						throw new InvalidHTTPMessageException("No chunk size in chunked response");
					int chunkLen;
					try{
						int lenEnd = chunkHeaderEnd;
						for(int j = index; j < lenEnd; j++){
							if(data[j] == ';'){
								lenEnd = j;
								break;
							}
						}
						chunkLen = Integer.parseInt(new String(data, index, lenEnd - index), 16);
					}catch(NumberFormatException e){
						throw new InvalidHTTPMessageException("Invalid chunk size", e);
					}
					chunkHeaderEnd += EOL.length;
					int datasize = data.length - chunkHeaderEnd;
					if(datasize >= chunkLen + EOL.length){
						byte[] chunkdata = Arrays.copyOfRange(data, chunkHeaderEnd, chunkHeaderEnd + chunkLen);
						this.newData(chunkdata);
						index = chunkHeaderEnd + chunkLen + EOL.length;
					}else{
						int write = Math.min(datasize, chunkLen);
						this.writeToChunkBuffer(data, chunkHeaderEnd, write);
						this.lastChunkRemaining = chunkLen + EOL.length - datasize;
						index = data.length;
					}
				}else{
					if(index > 0)
						throw new InvalidHTTPMessageException("End of incomplete chunk can only be at start of packet");
					if(this.lastChunkRemaining <= data.length){
						int write = this.lastChunkRemaining - EOL.length;
						if(write > 0)
							this.writeToChunkBuffer(data, 0, write);
						if(this.chunkBufferIndex > 0)
							this.newData(Arrays.copyOf(this.chunkBuffer, this.chunkBufferIndex));
						else if(write <= 0)
							this.end();
						index += this.lastChunkRemaining;
						this.chunkBufferIndex = 0;
						this.lastChunkRemaining = 0;
					}else{
						this.writeToChunkBuffer(data, 0, data.length);
						this.lastChunkRemaining -= data.length;
						index = data.length;
					}
				}
			}
		}else{
			this.receivedData += data.length;
			if(data.length > 0)
				this.newData(data);
			if(this.totalSize >= 0 && this.receivedData >= this.totalSize)
				this.end();
		}
	}

	private void writeToChunkBuffer(byte[] src, int srcIndex, int len) throws IOException {
		int written = 0;
		while(written < len){
			int write = Math.min(len - written, this.chunkBuffer.length - this.chunkBufferIndex);
			System.arraycopy(src, srcIndex, this.chunkBuffer, this.chunkBufferIndex, write);
			this.chunkBufferIndex += write;
			if(this.chunkBufferIndex >= this.chunkBuffer.length){
				this.chunkBufferIndex = 0;
				this.newData(this.chunkBuffer);
			}
			written += write;
			srcIndex += write;
		}
	}

	private void newData(byte[] data) throws IOException {
		if(this.ended)
			throw new InvalidHTTPMessageException("Data after end");
		if(data.length == 0)
			this.ended = true;
		this.onData.accept(data);
	}


	public void end() throws IOException {
		if(!this.ended)
			this.newData(new byte[0]);
	}

	public boolean hasReceivedAllData() {
		return this.totalSize < 0 || this.receivedData >= this.totalSize;
	}

	public boolean hasEnded() {
		return this.ended;
	}
}
