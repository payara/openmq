/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * @(#)PHashMap.java	1.19 08/28/07
 */ 

package com.sun.messaging.jmq.io.disk;

import com.sun.messaging.jmq.resources.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.*;

/**
 * PHashMap is a persistent HashMap that is backed by a file.
 * After instantiated, the PHashMap should be loaded first by calling
 * <code>loaded()</code> before any other methods is used.
 */
public class PHashMap extends ConcurrentHashMap {

    private static boolean DEBUG = false;

    public static final int VERSION = 1;

    public static final int DEFAULT_INITIAL_MAP_CAPACITY = 128;

    protected VRFile backingFile;
    protected ConcurrentHashMap recordMap; // maps the key -> VRecord
    protected boolean safe = false;

    // set in VRFileRAF to identify backing file for PHashMap
    protected static final long cookie
	= String.valueOf("PHashMap:"+VERSION).hashCode();

    private VRFileWarning warning = null;

    protected boolean loaded = false;

    /**
     * Construct the hash table backed by the specified file.
     * If the file exists, the hash table will be initialized
     * with entries from the file.
     * @param filename	Backing file.
     * @param safe		Indicate whether the underlying file
     *				should be sync'ed as soon as possible.
     * @param reset		If true, all data in the file will be cleared.
     */
    public PHashMap(File filename, boolean safe, boolean reset, boolean isMinimumWrites, boolean interruptSafe)
	throws IOException {

	this(filename, VRFileRAF.DEFAULT_INITIAL_FILE_SIZE,
            DEFAULT_INITIAL_MAP_CAPACITY, safe, reset, isMinimumWrites, interruptSafe);
    }

    /**
     * Construct the hash table backed by the specified file.
     * If the file exists, the hash table will be initialized
     * with entries from the file.
     * @param filename	Backing file.
     * @param size		Initialize size of backing file.
     * @param safe		Indicate whether the underlying file
     *				should be sync'ed as soon as possible.
     * @param reset		If true, all data in the file will be cleared.
     */
    public PHashMap(File filename, long size, boolean safe, boolean reset, boolean isMinimumWrites, boolean interruptSafe)
        throws IOException {

        this(filename, size, DEFAULT_INITIAL_MAP_CAPACITY, safe, reset, isMinimumWrites, interruptSafe);
    }

    /**
     * Construct the hash table backed by the specified file.
     * If the file exists, the hash table will be initialized
     * with entries from the file.
     * @param filename	Backing file.
     * @param size		Initialize size of backing file.
     * @param mapCapacity	Initialize map capacity.
     * @param safe		Indicate whether the underlying file
     *				should be sync'ed as soon as possible.
     * @param reset		If true, all data in the file will be cleared.
     */
    public PHashMap(File filename, long size, int mapCapacity,
        boolean safe, boolean reset, boolean isMinimumWrites, boolean interruptSafe) throws IOException {

        super(mapCapacity);

	if (DEBUG) {
	    System.out.println("PHashMap cookie: " + cookie);
	}

	this.safe = safe;

	initBackingFile(filename, size, isMinimumWrites, interruptSafe);
	backingFile.setCookie(cookie);
	backingFile.setSafe(safe);

	if (reset) {
	    backingFile.clear(false);
	}

	try {
	    backingFile.open();
	} catch (VRFileWarning w) {
	    warning = w;
	}
    }

    protected void initBackingFile(File filename, long size, boolean isMinimumWrites, boolean interruptSafe) {
	backingFile = new VRFileRAF(filename, size, isMinimumWrites, interruptSafe);
    }

    public void load(ObjectInputStreamCallback ocb) throws IOException, ClassNotFoundException,
	PHashMapLoadException {

	PHashMapLoadException loadException = null;

	Set entries = backingFile.getRecords();
        int eSize = entries.size();
        int mSize = this.size();
        if (eSize > mSize) {
            recordMap = new ConcurrentHashMap(eSize);
        } else {
            recordMap = new ConcurrentHashMap(mSize);
        }

	Iterator iter = entries.iterator();
	while (iter.hasNext()) {
	    VRecordRAF record = (VRecordRAF)iter.next();

	    Object key = null;
	    Object value = null;
	    Throwable kex = null;
	    Throwable vex = null;
	    Throwable ex = null;
	    try {
	    	byte[] buf = new byte[record.getDataCapacity()];
	    	record.read(buf);
	    	ByteArrayInputStream bais = new ByteArrayInputStream(buf);

                // Use our version of ObjectInputStream so we can load old
                // serialized object from an old store, i.e. store migration
                ObjectInputStream ois = ocb.getObjectInputStream(bais);
	    	try {
		    key = ois.readObject();
	    	} catch (Throwable e) {
		    if (e instanceof ClassNotFoundException) {
			throw (ClassNotFoundException)e;
		    } else {
			kex = e;
		    }
		}

		try {
		    value = ois.readObject();
		} catch (Throwable e) {
		    if (e instanceof ClassNotFoundException) {
			throw (ClassNotFoundException)e;
		    } else {
			vex = e;
		    }
		}
		ois.close();
		bais.close();
	    } catch (IOException e) {
		ex = e;
	    }

	    if (kex != null || vex != null || ex != null) {

		PHashMapLoadException le = new PHashMapLoadException(
                    "Failed to load data in [" + record.toString() + "]");
		le.setKey(key);
		le.setValue(value);
		le.setKeyCause(kex);
		le.setValueCause(vex);
		le.setNextException(loadException);
		le.initCause(ex);
		loadException = le;

		if (key != null && value != null) {
		    // we have the key, keep the record
		    recordMap.put(key, record);
		    super.put(key, value);
		} else {
		    // delete bad record
		    backingFile.free(record);
		}
	    } else {
		// cache info
		recordMap.put(key, record);
		super.put(key, value);
	    }
	}

	loaded = true;

	if (loadException != null) {
	    throw loadException;
	}
    }

    /**
     * Forces any changes made to the hash map to be written to disk.
     */
    public void force() throws IOException {
	force(null);
    }

    public void force(Object key) throws IOException {
	checkLoaded();

	if (key != null) {
	    VRecord record = (VRecord)recordMap.get(key);
	    if (record != null) {
		record.force();
	    }
	} else {
	    // sync the whole file
	    backingFile.force();
	}
    }

    public Object put(Object key, Object value) {
        return doPut(key, value, false);
    }

    public Object putIfAbsent(Object key, Object value) {
        return doPut(key, value, true);
    }

    public final Object put(Object key, Object value, boolean putIfAbsent) {
        return doPut(key, value, putIfAbsent);
    }

    /**
     * Maps the specified key to the specified value in this HashMap.
     * The entry will be persisted in the backing file.
     */
    Object doPut(Object key, Object value, boolean putIfAbsent) {
	checkLoaded();

        boolean error = false;
        Object oldValue = null;
	try {
            if (putIfAbsent) {
                oldValue = super.putIfAbsent(key, value);
                if (oldValue != null) {
                    // nothing to do since there was a mapping for key
                    return oldValue;
                }
            } else {
                oldValue = super.put(key, value);
            }

	    // serialize the key and value
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
	    ObjectOutputStream bos = new ObjectOutputStream(baos);

	    bos.writeObject(key);
	    bos.writeObject(value);
	    bos.close();

	    byte[] data = baos.toByteArray();

            VRecordRAF record = (VRecordRAF)recordMap.get(key);

            synchronized (backingFile) {
                if (record == null) {
                    record = (VRecordRAF)backingFile.allocate(data.length);
                } else {
                    if (record.getDataCapacity() < data.length) {
                        // need another VRecordRAF
                        backingFile.free(record);
                        record = (VRecordRAF)backingFile.allocate(data.length);
                    } else {
                        record.rewind();
                    }
                }
                record.write(data);

                if (safe) {
                    record.force();
                }
            }

            // update internal records map
            recordMap.put(key, record);
	} catch (IOException e) {
            error = true;
	    throw new RuntimeException(e);
	} finally {
            if (error) {
                if (oldValue == null) {
                    super.remove(key);
                } else {
                    super.put(key, oldValue); // put back the old value
                }
            }
        }

        return oldValue;
    }

    @Override
    public Object remove(Object key) {
	checkLoaded();

        try {
            Object old = super.remove(key);
            removeFromFile(key);
	    return old;
	} catch (IOException e) {
	    throw new RuntimeException(e);
	}
    }

    public boolean remove(Object key, Object value) {
        throw new UnsupportedOperationException("Operation not supported");
    }

    public Object replace(Object key, Object value) {
        throw new UnsupportedOperationException("Operation not supported");
    }

    public boolean replace(Object key, Object oldValue, Object newValue) {
        throw new UnsupportedOperationException("Operation not supported");
    }

    @Override
    public void clear() {
	checkLoaded();

	try {
            super.clear();
            recordMap.clear();
            backingFile.clear(false);
	} catch (IOException e) {
	    throw new RuntimeException(e);
	}
    }

    public void close() {
	backingFile.close();
    }

    public Set entrySet() {
	checkLoaded();

	Set set = entrySet;
        if (set == null) {
            entrySet = new HashSet(super.entrySet());
            set = entrySet;
        }

	return set;
    }

    @Override
    public ConcurrentHashMap.KeySetView keySet() {
	checkLoaded();

	return super.keySet();
    }

    public VRFileWarning getWarning() {
	return warning;
    }

    public Collection values() {
	checkLoaded();

	Collection c = values;
        if (c == null) {
            values = new ValueCollection(super.entrySet());
            c = values;
        }
	return c;
    }

    protected final void checkLoaded() {
	if (!loaded) {
	    throw new IllegalStateException(
			SharedResources.getResources().getString(
				SharedResources.E_VRFILE_NOT_OPEN));
	}
    }

    final Object putInHashMap(Object key, Object value, boolean putIfAbsent) {
	if (putIfAbsent) {
            return super.putIfAbsent(key, value);
        } else {
            return super.put(key, value);
        }
    }

    // Views: key set, entry set, and value collection

    private Set entrySet = null;
    private Collection values = null;

    private void removeFromFile(Object key) throws IOException {
	if (DEBUG) {
	    System.out.println("PHashMap.removeFromFile() called for " + key);
	}

	VRecord record = (VRecord)recordMap.remove(key);
	if (record != null) {
            synchronized (backingFile) {
                backingFile.free(record);
                if (safe) {
                    backingFile.force();
                }
            }
	}
    }

    private class HashSet extends AbstractSet {
	Set set = null;
	HashSet(Set set) {
	    this.set = set;
	}

	public int size() {
	    return set.size();
	}

	public boolean contains(Object o) {
	    return set.contains(o);
	}

	public boolean remove(Object o) {
	    boolean b = set.remove(o);
	    if (b) {
		Object key = null;
		if (o instanceof Map.Entry) {
		    key = ((Map.Entry)o).getKey();
		} else {
		    key = o;
		}
		try {
		    removeFromFile(key);
		} catch (IOException t) {
		    throw new RuntimeException(t);
		}
	    }
	    return b;
	}

	public void clear() {
	    PHashMap.this.clear();
	}

	public Iterator iterator() {
	    return new HashIterator(set.iterator());
	}
    }

    private class ValueCollection extends AbstractCollection {
	Set entries = null;

	ValueCollection(Set e) {
	    this.entries = e;
	}

	public int size() {
	    return entries.size();
	}

	public boolean contains(Object o) {
	    return containsValue(o);
	}

	public void clear() {
	    PHashMap.this.clear();
	}

	public Iterator iterator() {
	    return new HashIterator(entries.iterator(), true);
	}
    }

    // Iterator for entry set, key set and values collection
    private class HashIterator implements Iterator {
	boolean values = false;
	Iterator iterator = null;
	Object current = null;

	HashIterator(Iterator itor) {
	    this.iterator = itor;
	}

	HashIterator(Iterator itor, boolean values) {
	    this.iterator = itor;
	    this.values = values;
	}

	public boolean hasNext() {
	    return iterator.hasNext();
	}

	public Object next() {
	    current = iterator.next();
	    if (values) {
		return ((Map.Entry)current).getValue();
	    } else {
	    	return current;
	    }
	}

	public void remove() {
	    iterator.remove();
	    Object key = null;
	    if (current instanceof Map.Entry) {
		key = ((Map.Entry)current).getKey();
	    } else {
		key = current;
	    }
	    try {
		removeFromFile(key);
	    } catch (IOException t) {
		throw new RuntimeException(t);
	    }
	}
    }
}

