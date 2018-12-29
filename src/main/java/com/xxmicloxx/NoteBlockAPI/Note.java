package com.xxmicloxx.NoteBlockAPI;

/**
 * Represents a note played; contains the instrument and the key
 * @see NotePitch
 *
 */
public class Note {

	private byte instrument;
	private byte key;
	
	public Note(byte instrument, byte key) {
		this.instrument = instrument;
		this.key = key;
	}

	/**
	 * Gets instrument number
	 */
	public byte getInstrument() {
		return instrument;
	}

	/**
	 * Sets instrument number
	 */
	public void setInstrument(byte instrument) {
		this.instrument = instrument;
	}

	public byte getKey() {
		return key;
	}

	public void setKey(byte key) {
		this.key = key;
	}
}
