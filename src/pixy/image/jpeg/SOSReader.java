/**
 * Copyright (C) 2014-2019 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 */

package pixy.image.jpeg;

import java.io.IOException;

import pixy.util.Reader;

public class SOSReader implements Reader {
	//
	private Segment segment;
	private SOFReader reader;
	
	int Ss, Se, Ah_Al, Ah, Al;
	
	public SOSReader(Segment segment) throws IOException {
		//
		if(segment.getMarker() != Marker.SOS) {
			throw new IllegalArgumentException("Not a valid SOS segment!");
		}
		
		this.segment = segment;
		read();
	}
	
	public SOSReader(Segment segment, SOFReader reader) throws IOException {
		//
		if(segment.getMarker() != Marker.SOS) {
			throw new IllegalArgumentException("Not a valid SOS segment!");
		}
		
		this.segment = segment;
		this.reader = reader;
		read();
	}
	
	public void read() throws IOException {
		//
		byte[] data = segment.getData();
		int count = 0;

		// Tolerate a truncated SOS header and a missing SOF (null reader / null components) without
		// throwing: a malformed scan header must not abort metadata reading of the whole file.
		if (data.length == 0) return;
		int numOfComponents = data[count++] & 0xff;
		Component[] components = (reader != null) ? reader.getComponents() : null;

		for(int i = 0; i < numOfComponents; i++) {
			if (count + 2 > data.length) return;
			byte id = data[count++];
			byte tbl_no = data[count++];

			if (components != null) {
				for(Component component : components) {
					if(component.getId() == id) {
						component.setACTableNumber((byte)(tbl_no&0x0f));
						component.setDCTableNumber((byte)((tbl_no>>4)&0x0f));
						break;
					}
				}
			}
		}

		if (count + 3 > data.length) return;
		//Start of spectral or predictor selection
		Ss = data[count++];
	    //End of spectral selection
		Se = data[count++];
		//Ah: Successive approximation bit position high
		//Al: Successive approximation bit position low or point transform
		Ah_Al = data[count++];
	    Ah = (Ah_Al>>4)&0x0f;
		Al = Ah_Al&0x0f;
	}
	
	public void setSOFReader(SOFReader reader) {
		this.reader = reader;
	}
}
