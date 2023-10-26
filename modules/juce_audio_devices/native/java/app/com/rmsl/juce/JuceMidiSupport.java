/*
  ==============================================================================

   This file is part of the JUCE library.
   Copyright (c) 2022 - Raw Material Software Limited

   JUCE is an open source library subject to commercial or open-source
   licensing.

   The code included in this file is provided under the terms of the ISC license
   http://www.isc.org/downloads/software-support-policy/isc-license. Permission
   To use, copy, modify, and/or distribute this software for any purpose with or
   without fee is hereby granted provided that the above copyright notice and
   this permission notice appear in all copies.

   JUCE IS PROVIDED "AS IS" WITHOUT ANY WARRANTY, AND ALL WARRANTIES, WHETHER
   EXPRESSED OR IMPLIED, INCLUDING MERCHANTABILITY AND FITNESS FOR PURPOSE, ARE
   DISCLAIMED.

  ==============================================================================
*/

package com.rmsl.juce;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.IntentFilter;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.BluetoothDevice;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.Pair;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

import static android.content.Context.MIDI_SERVICE;
import static android.content.Context.BLUETOOTH_SERVICE;

public class JuceMidiSupport
{
// Define Bluetooth Callback for when it is switched off
    BluetoothAdapter adapterStatusCheck = BluetoothAdapter.getDefaultAdapter ();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            // It means the user has changed his bluetooth state.
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {

                if (adapterStatusCheck.getState() == BluetoothAdapter.STATE_TURNING_OFF) {
                    // The user bluetooth is turning off yet, but it is not disabled yet.
                    return;
                }

                if (adapterStatusCheck.getState() == BluetoothAdapter.STATE_OFF) {
                    // The user bluetooth is already disabled.
                    return;
                }

            }
        }

    };

    //==============================================================================
    public interface JuceMidiPort
    {
        boolean isInputPort ();

        // start, stop does nothing on an output port
        void start ();

        void stop ();

        void close ();

        // send will do nothing on an input port
        void sendMidi (byte[] msg, int offset, int count);

        String getName ();
    }

    static BluetoothAdapter getDefaultBluetoothAdapter (Context ctx)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2)
            return BluetoothAdapter.getDefaultAdapter();

        return ((BluetoothManager) ctx.getSystemService (BLUETOOTH_SERVICE)).getAdapter();
    }

    //==============================================================================
    public static class BluetoothMidiManager extends ScanCallback
    {
        BluetoothMidiManager (Context contextToUse)
        {
            appContext = contextToUse;
        }

        public String[] getMidiBluetoothAddresses ()
        {
            return bluetoothMidiDevices.toArray (new String[bluetoothMidiDevices.size ()]);
        }

        public String getHumanReadableStringForBluetoothAddress (String address)
        {
            BluetoothDevice btDevice = getDefaultBluetoothAdapter (appContext).getRemoteDevice (address);
            return btDevice.getName ();
        }

        public int getBluetoothDeviceStatus (String address)
        {
            return getAndroidMidiDeviceManager (appContext).getBluetoothDeviceStatus (address);
        }

        public void startStopScan (boolean shouldStart)
        {
            BluetoothAdapter bluetoothAdapter = getDefaultBluetoothAdapter (appContext);

            if (bluetoothAdapter == null)
            {
                Log.d ("JUCE", "BluetoothMidiManager error: could not get default Bluetooth adapter");
                return;
            }

            BluetoothLeScanner bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner ();

            if (bluetoothLeScanner == null)
            {
                Log.d ("JUCE", "BluetoothMidiManager error: could not get Bluetooth LE scanner");
                return;
            }

            if (shouldStart)
            {
                ScanFilter.Builder scanFilterBuilder = new ScanFilter.Builder ();
                scanFilterBuilder.setServiceUuid (ParcelUuid.fromString (bluetoothLEMidiServiceUUID));

                ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder ();
                scanSettingsBuilder.setCallbackType (ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setScanMode (ScanSettings.SCAN_MODE_LOW_POWER)
                        .setScanMode (ScanSettings.MATCH_MODE_STICKY);

                bluetoothLeScanner.startScan (Arrays.asList (scanFilterBuilder.build ()),
                        scanSettingsBuilder.build (),
                        this);
            } else
            {
                bluetoothLeScanner.stopScan (this);
            }
        }

        public boolean pairBluetoothMidiDevice (String address)
        {
            Log.i("JUCE","Starting Connection to BLE Device.");
            Log.i("JUCE","Test Test");

            BluetoothDevice btDevice = getDefaultBluetoothAdapter (appContext).getRemoteDevice (address);

            if (btDevice == null)
            {
                Log.d ("JUCE", "failed to create buletooth device from address");
                return false;
            }

            return getAndroidMidiDeviceManager (appContext).pairBluetoothDevice (btDevice);
        }

        public boolean unpairBluetoothMidiDevice (String address)
        {
            boolean retval;
            retval = getAndroidMidiDeviceManager (appContext).unpairBluetoothDevice (address);

            return retval;
        }

        public void onScanFailed (int errorCode)
        {
        }

        public void onScanResult (int callbackType, ScanResult result)
        {
            if (callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                    || callbackType == ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            {
                BluetoothDevice device = result.getDevice ();

                if (device != null)
                    bluetoothMidiDevices.add (device.getAddress ());
            }

            if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST)
            {
                Log.d ("JUCE", "ScanSettings.CALLBACK_TYPE_MATCH_LOST");
                BluetoothDevice device = result.getDevice ();

                if (device != null)
                {
                    bluetoothMidiDevices.remove (device.getAddress ());
                    unpairBluetoothMidiDevice (device.getAddress ());
                }
            }
        }

        public void onBatchScanResults (List<ScanResult> results)
        {
            for (ScanResult result : results)
                onScanResult (ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
        }

        private BluetoothLeScanner scanner;
        private static final String bluetoothLEMidiServiceUUID = "03B80E5A-EDE8-4B33-A751-6CE34EC4C700";

        private HashSet<String> bluetoothMidiDevices = new HashSet<String> ();
        private Context appContext = null;
    }

    public static class JuceMidiInputPort extends MidiReceiver implements JuceMidiPort
    {
        private native void handleReceive (long host, byte[] msg, int offset, int count, long timestamp);

        public JuceMidiInputPort (MidiDeviceManager mm, MidiOutputPort actualPort, MidiPortPath portPathToUse, long hostToUse)
        {
            owner = mm;
            androidPort = actualPort;
            portPath = portPathToUse;
            juceHost = hostToUse;
            isConnected = false;
        }

        @Override
        protected void finalize () throws Throwable
        {
            close ();
            super.finalize ();
        }

        @Override
        public boolean isInputPort ()
        {
            return true;
        }

        @Override
        public void start ()
        {
            if (owner != null && androidPort != null && !isConnected)
            {
                androidPort.connect (this);
                isConnected = true;
            }
        }

        @Override
        public void stop ()
        {
            if (owner != null && androidPort != null && isConnected)
            {
                androidPort.disconnect (this);
                isConnected = false;
            }
        }

        @Override
        public void close ()
        {
            if (androidPort != null)
            {
                try
                {
                    androidPort.close ();
                } catch (IOException exception)
                {
                    Log.d ("JUCE", "IO Exception while closing port");
                }
            }

            if (owner != null)
                owner.removePort (portPath);

            owner = null;
            androidPort = null;
        }

        @Override
        public void onSend (byte[] msg, int offset, int count, long timestamp)
        {
            if (count > 0)
                handleReceive (juceHost, msg, offset, count, timestamp);
        }

        @Override
        public void onFlush ()
        {}

        @Override
        public void sendMidi (byte[] msg, int offset, int count)
        {
        }

        @Override
        public String getName ()
        {
            return owner.getPortName (portPath);
        }

        MidiDeviceManager owner;
        MidiOutputPort androidPort;
        MidiPortPath portPath;
        long juceHost;
        boolean isConnected;
    }

    public static class JuceMidiOutputPort implements JuceMidiPort
    {
        public JuceMidiOutputPort (MidiDeviceManager mm, MidiInputPort actualPort, MidiPortPath portPathToUse)
        {
            owner = mm;
            androidPort = actualPort;
            portPath = portPathToUse;
        }

        @Override
        protected void finalize () throws Throwable
        {
            close ();
            super.finalize ();
        }

        @Override
        public boolean isInputPort ()
        {
            return false;
        }

        @Override
        public void start ()
        {
        }

        @Override
        public void stop ()
        {
        }

        @Override
        public void sendMidi (byte[] msg, int offset, int count)
        {
            if (androidPort != null)
            {
                try
                {
                    androidPort.send (msg, offset, count);
                } catch (IOException exception)
                {
                    Log.d ("JUCE", "send midi had IO exception");
                }
            }
        }

        @Override
        public void close ()
        {
            if (androidPort != null)
            {
                try
                {
                    androidPort.close ();
                } catch (IOException exception)
                {
                    Log.d ("JUCE", "IO Exception while closing port");
                }
            }

            if (owner != null)
                owner.removePort (portPath);

            owner = null;
            androidPort = null;
        }

        @Override
        public String getName ()
        {
            return owner.getPortName (portPath);
        }

        MidiDeviceManager owner;
        MidiInputPort androidPort;
        MidiPortPath portPath;
    }

    private static class MidiPortPath extends Object
    {
        public MidiPortPath (int deviceIdToUse, boolean direction, int androidIndex)
        {
            deviceId = deviceIdToUse;
            isInput = direction;
            portIndex = androidIndex;
        }

        public int deviceId;
        public int portIndex;
        public boolean isInput;

        @Override
        public int hashCode ()
        {
            Integer i = new Integer ((deviceId * 128) + (portIndex < 128 ? portIndex : 127));
            return i.hashCode () * (isInput ? -1 : 1);
        }

        @Override
        public boolean equals (Object obj)
        {
            if (obj == null)
                return false;

            if (getClass () != obj.getClass ())
                return false;

            MidiPortPath other = (MidiPortPath) obj;
            return (portIndex == other.portIndex && isInput == other.isInput && deviceId == other.deviceId);
        }
    }

    //==============================================================================
    public static class MidiDeviceManager extends MidiManager.DeviceCallback implements MidiManager.OnDeviceOpenedListener
    {
        //==============================================================================
        private class DummyBluetoothGattCallback extends BluetoothGattCallback
        {
            public DummyBluetoothGattCallback (MidiDeviceManager mm)
            {
                super ();
                owner = mm;
            }

            public void onConnectionStateChange (BluetoothGatt gatt, int status, int newState)
            {

                Log.d("JUCE","========== GATT New State");
                Log.d("JUCE","GATT Status: " + newState);
                if ( status != BluetoothGatt.GATT_SUCCESS ) {
                    Log.d("JUCE","GATT NOT SECCESSFULL");
                }

                if (newState == BluetoothProfile.STATE_CONNECTED)
                {
Log.d("JUCE","BLE Device " + gatt.getDevice().getAddress() + " GATT STATUS_CONNECTED");

                    gatt.requestConnectionPriority (BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    owner.pairBluetoothDeviceStepTwo (gatt.getDevice ());

                }else if (newState == BluetoothProfile.STATE_DISCONNECTED)
                {

                    Log.d("JUCE","BLE Device " + gatt.getDevice().getAddress() + " GATT STATUS_DISCONNECTED");
                    unpairBluetoothDevice(gatt.getDevice().getAddress());

                }
            }

            public void onServicesDiscovered (BluetoothGatt gatt, int status) {}

            public void onCharacteristicRead (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {}

            public void onCharacteristicWrite (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {}

            public void onCharacteristicChanged (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {}

            public void onDescriptorRead (BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {}

            public void onDescriptorWrite (BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {}

            public void onReliableWriteCompleted (BluetoothGatt gatt, int status) {}

            public void onReadRemoteRssi (BluetoothGatt gatt, int rssi, int status) {}

            public void onMtuChanged (BluetoothGatt gatt, int mtu, int status) {}

            private MidiDeviceManager owner;
        }

        //==============================================================================
        private class MidiDeviceOpenTask extends java.util.TimerTask
        {
            public MidiDeviceOpenTask (MidiDeviceManager deviceManager, MidiDevice device, BluetoothGatt gattToUse)
            {
                owner = deviceManager;
                midiDevice = device;
                btGatt = gattToUse;
            }

            @Override
            public boolean cancel ()
            {
                synchronized (MidiDeviceOpenTask.class)
                {
                    owner = null;
                    boolean retval = super.cancel ();

                    if (btGatt != null)
                    {
                        btGatt.disconnect ();
                        btGatt.close ();

                        btGatt = null;
                    }

                    if (midiDevice != null)
                    {
                        try
                        {
                            midiDevice.close ();
                        } catch (IOException e)
                        {
                        }

                        midiDevice = null;
                    }

                    return retval;
                }
            }

            public String getBluetoothAddress ()
            {
                synchronized (MidiDeviceOpenTask.class)
                {
                    if (midiDevice != null)
                    {
                        MidiDeviceInfo info = midiDevice.getInfo ();
                        if (info.getType () == MidiDeviceInfo.TYPE_BLUETOOTH)
                        {
                            BluetoothDevice btDevice = (BluetoothDevice) info.getProperties ().get (info.PROPERTY_BLUETOOTH_DEVICE);
                            if (btDevice != null)
                                return btDevice.getAddress ();
                        }
                    }
                }

                return "";
            }

            public BluetoothGatt getGatt () { return btGatt; }

            public int getID ()
            {
                return midiDevice.getInfo ().getId ();
            }

            @Override
            public void run ()
            {
                synchronized (MidiDeviceOpenTask.class)
                {
                    if (owner != null && midiDevice != null)
                        owner.onDeviceOpenedDelayed (midiDevice);
                }
            }

            private MidiDeviceManager owner;
            private MidiDevice midiDevice;
            private BluetoothGatt btGatt;
        }

        //==============================================================================
        public MidiDeviceManager (Context contextToUse)
        {
            appContext = contextToUse;
            manager = (MidiManager) appContext.getSystemService (MIDI_SERVICE);

            if (manager == null)
            {
                Log.d ("JUCE", "MidiDeviceManager error: could not get MidiManager system service");
                return;
            }

            MidiDeviceInfo[] foundDevices = manager.getDevices ();

            for (MidiDeviceInfo info : foundDevices)
                onDeviceAdded (info);

            manager.registerDeviceCallback (this, null);
        }

        protected void finalize () throws Throwable
        {
            manager.unregisterDeviceCallback (this);

            synchronized (MidiDeviceManager.class)
            {
                btDevicesPairing.clear ();

                for (Integer deviceID : openTasks.keySet ())
                    openTasks.get (deviceID).cancel ();

                openTasks = null;
            }

            for (MidiPortPath key : openPorts.keySet ())
                openPorts.get (key).get ().close ();

            openPorts = null;

            for (Pair<MidiDevice, BluetoothGatt> device : midiDevices)
            {
                if (device.second != null)
                {
                    device.second.disconnect ();
                    device.second.close ();
                }

                device.first.close ();
            }

            midiDevices.clear ();

            super.finalize ();
        }

        public String[] getJuceAndroidMidiOutputDeviceNameAndIDs ()
        {
            return getJuceAndroidMidiDeviceNameAndIDs (MidiDeviceInfo.PortInfo.TYPE_OUTPUT);
        }

        public String[] getJuceAndroidMidiInputDeviceNameAndIDs ()
        {
            return getJuceAndroidMidiDeviceNameAndIDs (MidiDeviceInfo.PortInfo.TYPE_INPUT);
        }

        private String[] getJuceAndroidMidiDeviceNameAndIDs (int portType)
        {
            // only update the list when JUCE asks for a new list
            synchronized (MidiDeviceManager.class)
            {
                deviceInfos = getDeviceInfos ();
            }

            ArrayList<String> portNameAndIDs = new ArrayList<String> ();

            for (MidiPortPath portInfo  : getAllPorts (portType))
            {
                portNameAndIDs.add (getPortName (portInfo));
                portNameAndIDs.add (Integer.toString (portInfo.hashCode ()));
            }

            String[] names = new String[portNameAndIDs.size ()];
            return portNameAndIDs.toArray (names);
        }

        private JuceMidiPort openMidiPortWithID (int deviceID, long host, boolean isInput)
        {
            synchronized (MidiDeviceManager.class)
            {
                int portTypeToFind = (isInput ? MidiDeviceInfo.PortInfo.TYPE_INPUT : MidiDeviceInfo.PortInfo.TYPE_OUTPUT);
                MidiPortPath portInfo = getPortPathForID (portTypeToFind, deviceID);

                if (portInfo != null)
                {
                    // ports must be opened exclusively!
                    if (openPorts.containsKey (portInfo))
                        return null;

                    Pair<MidiDevice, BluetoothGatt> devicePair = getMidiDevicePairForId (portInfo.deviceId);

                    if (devicePair != null)
                    {
                        MidiDevice device = devicePair.first;
                        if (device != null)
                        {
                            JuceMidiPort juceMidiPort = null;

                            if (isInput)
                            {
                                MidiOutputPort outputPort = device.openOutputPort (portInfo.portIndex);

                                if (outputPort != null)
                                    juceMidiPort = new JuceMidiInputPort (this, outputPort, portInfo, host);
                            } else
                            {
                                MidiInputPort inputPort = device.openInputPort (portInfo.portIndex);

                                if (inputPort != null)
                                    juceMidiPort = new JuceMidiOutputPort (this, inputPort, portInfo);
                            }

                            if (juceMidiPort != null)
                            {
                                openPorts.put (portInfo, new WeakReference<JuceMidiPort> (juceMidiPort));

                                return juceMidiPort;
                            }
                        }
                    }
                }
            }

            return null;
        }

        public JuceMidiPort openMidiInputPortWithID (int deviceID, long host)
        {
            return openMidiPortWithID (deviceID, host, true);
        }

        public JuceMidiPort openMidiOutputPortWithID (int deviceID)
        {
            return openMidiPortWithID (deviceID, 0, false);
        }

        /* 0: unpaired, 1: paired, 2: pairing */
        public int getBluetoothDeviceStatus (String address)
        {
            synchronized (MidiDeviceManager.class)
            {
                if (!address.isEmpty ())
                {
                    if (findMidiDeviceForBluetoothAddress (address) != null){
                        Log.d("JUCE","BLE Device is listed as MIDI device");
                        return 1;
                    }

                    if (btDevicesPairing.containsKey (address)) {
                        Log.d("JUCE","BLE Device is pairing");
                        return 2;
                    }

                    if (findOpenTaskForBluetoothAddress (address) != null) {
                        Log.d("JUCE","BLE Device has a task open");
                        return 2;
                    }
                }
            }

            return 0;
        }

        public boolean pairBluetoothDevice (BluetoothDevice btDevice)
        {

            Log.d("JUCE","BLE Device - Attempting Pairing");

            String btAddress = btDevice.getAddress ();
            if (btAddress.isEmpty ()){
                Log.d("JUCE","BLE Device unavailable - Cannot connect");
                return false;
            }

            synchronized (MidiDeviceManager.class)
            {
                if (getBluetoothDeviceStatus (btAddress) != 0) {
                    Log.d("JUCE","BLE Device is busy - Cannot connect");
                    return false;
                }

                Log.d("JUCE","Add device to Pairing List");
                btDevicesPairing.put (btDevice.getAddress (), null);
                
                Log.d("JUCE","Connecting GATT");
                BluetoothGatt gatt = btDevice.connectGatt (appContext.getApplicationContext (), false, new DummyBluetoothGattCallback (this), 2);

                if (gatt != null)
                {
                    Log.d("JUCE","GATT not null");
                    btDevicesPairing.put (btDevice.getAddress (), gatt);
                } else
                {
                    Log.d("JUCE","GATT null");
                    pairBluetoothDeviceStepTwo (btDevice);
                }
            }

            return true;
        }

        public void pairBluetoothDeviceStepTwo (BluetoothDevice btDevice)
        {
            manager.openBluetoothDevice (btDevice, this, null);
        }

        public boolean unpairBluetoothDevice (String address)
        {
            Log.i("JUCE","Unpairing device - new");

            if (address.isEmpty ())
                return false;

            synchronized (MidiDeviceManager.class)
            {
                Pair<MidiDevice, BluetoothGatt> midiDevicePair = findMidiDeviceForBluetoothAddress (address);
                if (midiDevicePair != null)
                {
                    MidiDevice midiDevice = midiDevicePair.first;
                    onDeviceRemoved (midiDevice.getInfo ());

                    try
                    {
                        Log.i("JUCE","Unpairing - closing Midi device");
                        midiDevice.close ();
                    } catch (IOException exception)
                    {
                        Log.d ("JUCE", "IOException while closing midi device");
                    }
                }

                Log.i("JUCE","Unpairing - gatt");
                if (btDevicesPairing.containsKey (address))
                {
                    Log.i("JUCE","Unpairing - finding gatt");
                    BluetoothGatt gatt = btDevicesPairing.get (address);
                    if (gatt != null)
                    {
                        Log.i("JUCE","Unpairing - disconnecting gatt");
                        gatt.disconnect ();
                        gatt.close ();
                    }

                    btDevicesPairing.remove (address);
                }

                Log.i("JUCE","Unpairing - disconnecting Open Task");
                MidiDeviceOpenTask openTask = findOpenTaskForBluetoothAddress (address);
                if (openTask != null) {
                    int deviceID = openTask.getID();
                    openTask.cancel();
                    openTasks.remove(deviceID);
                }

                return true;
            }
        }

        private Pair<MidiDevice, BluetoothGatt> findMidiDeviceForBluetoothAddress (String address)
        {
            for (Pair<MidiDevice, BluetoothGatt> midiDevice : midiDevices)
            {
                MidiDeviceInfo info = midiDevice.first.getInfo ();
                if (info.getType () == MidiDeviceInfo.TYPE_BLUETOOTH)
                {
                    BluetoothDevice btDevice = (BluetoothDevice) info.getProperties ().get (info.PROPERTY_BLUETOOTH_DEVICE);
                    if (btDevice != null && btDevice.getAddress ().equals (address))
                        return midiDevice;
                }
            }

            return null;
        }

        private MidiDeviceOpenTask findOpenTaskForBluetoothAddress (String address)
        {
            for (Integer deviceID : openTasks.keySet ())
            {
                MidiDeviceOpenTask openTask = openTasks.get (deviceID);
                if (openTask.getBluetoothAddress ().equals (address))
                    return openTask;
            }

            return null;
        }

        public void removePort (MidiPortPath path)
        {
            openPorts.remove (path);
        }

        @Override
        public void onDeviceAdded (MidiDeviceInfo info)
        {
            // only add standard midi devices
            if (info.getType () == info.TYPE_BLUETOOTH)
                return;

            manager.openDevice (info, this, null);
        }

        @Override
        public void onDeviceRemoved (MidiDeviceInfo info)
        {
            synchronized (MidiDeviceManager.class)
            {
                Pair<MidiDevice, BluetoothGatt> devicePair = getMidiDevicePairForId (info.getId ());

                if (devicePair != null)
                {
                    MidiDevice midiDevice = devicePair.first;
                    BluetoothGatt gatt = devicePair.second;

                    // close all ports that use this device
                    boolean removedPort = true;

                    while (removedPort == true)
                    {
                        removedPort = false;
                        for (MidiPortPath key : openPorts.keySet ())
                        {
                            if (key.deviceId == info.getId ())
                            {
                                openPorts.get (key).get ().close ();
                                removedPort = true;
                                break;
                            }
                        }
                    }

                    if (gatt != null)
                    {
                        gatt.disconnect ();
                        gatt.close ();
                    }

                    midiDevices.remove (devicePair);
                }
            }

            handleDevicesChanged();
        }

        @Override
        public void onDeviceStatusChanged (MidiDeviceStatus status)
        {
        }

        @Override
        public void onDeviceOpened (MidiDevice theDevice)
        {
            synchronized (MidiDeviceManager.class)
            {
                MidiDeviceInfo info = theDevice.getInfo ();
                int deviceID = info.getId ();
                BluetoothGatt gatt = null;
                boolean isBluetooth = false;

                if (!openTasks.containsKey (deviceID))
                {
                    if (info.getType () == MidiDeviceInfo.TYPE_BLUETOOTH)
                    {
                        isBluetooth = true;
                        BluetoothDevice btDevice = (BluetoothDevice) info.getProperties ().get (info.PROPERTY_BLUETOOTH_DEVICE);
                        if (btDevice != null)
                        {
                            String btAddress = btDevice.getAddress ();
                            if (btDevicesPairing.containsKey (btAddress))
                            {
                                gatt = btDevicesPairing.get (btAddress);
                                btDevicesPairing.remove (btAddress);
                            } else
                            {
                                // unpair was called in the mean time
                                try
                                {
                                    Pair<MidiDevice, BluetoothGatt> midiDevicePair = findMidiDeviceForBluetoothAddress (btDevice.getAddress ());
                                    if (midiDevicePair != null)
                                    {
                                        gatt = midiDevicePair.second;

                                        if (gatt != null)
                                        {
                                            gatt.disconnect ();
                                            gatt.close ();
                                        }
                                    }

                                    theDevice.close ();
                                } catch (IOException e)
                                {
                                }

                                return;
                            }
                        }
                    }

                    MidiDeviceOpenTask openTask = new MidiDeviceOpenTask (this, theDevice, gatt);
                    openTasks.put (deviceID, openTask);

                    new java.util.Timer ().schedule (openTask, (isBluetooth ? 2000 : 100));
                }
            }
        }

        public void onDeviceOpenedDelayed (MidiDevice theDevice)
        {
            synchronized (MidiDeviceManager.class)
            {
                int deviceID = theDevice.getInfo ().getId ();

                if (openTasks.containsKey (deviceID))
                {
                    if (!midiDevices.contains (theDevice))
                    {
                        BluetoothGatt gatt = openTasks.get (deviceID).getGatt ();
                        openTasks.remove (deviceID);
                        midiDevices.add (new Pair<MidiDevice, BluetoothGatt> (theDevice, gatt));
                        handleDevicesChanged();
                    }
                } else
                {
                    // unpair was called in the mean time
                    MidiDeviceInfo info = theDevice.getInfo ();
                    BluetoothDevice btDevice = (BluetoothDevice) info.getProperties ().get (info.PROPERTY_BLUETOOTH_DEVICE);
                    if (btDevice != null)
                    {
                        String btAddress = btDevice.getAddress ();
                        Pair<MidiDevice, BluetoothGatt> midiDevicePair = findMidiDeviceForBluetoothAddress (btDevice.getAddress ());
                        if (midiDevicePair != null)
                        {
                            BluetoothGatt gatt = midiDevicePair.second;

                            if (gatt != null)
                            {
                                gatt.disconnect ();
                                gatt.close ();
                            }
                        }
                    }

                    try
                    {
                        theDevice.close ();
                    } catch (IOException e)
                    {
                    }
                }
            }
        }

        public String getPortName (MidiPortPath path)
        {
            int portTypeToFind = (path.isInput ? MidiDeviceInfo.PortInfo.TYPE_INPUT : MidiDeviceInfo.PortInfo.TYPE_OUTPUT);

            synchronized (MidiDeviceManager.class)
            {
                for (MidiDeviceInfo info : deviceInfos)
                {
                    if (info.getId () == path.deviceId)
                    {
                        for (MidiDeviceInfo.PortInfo portInfo : info.getPorts ())
                        {
                            int portType = portInfo.getType ();
                            if (portType == portTypeToFind)
                            {
                                int portIndex = portInfo.getPortNumber ();
                                if (portIndex == path.portIndex)
                                {
                                    String portName = portInfo.getName ();
                                    if (portName.isEmpty ())
                                        portName = (String) info.getProperties ().get (info.PROPERTY_NAME);

                                    return portName;
                                }
                            }
                        }
                    }
                }
            }

            return "";
        }

        public ArrayList<MidiPortPath> getAllPorts (int portType)
        {
            ArrayList<MidiPortPath> ports = new ArrayList<MidiPortPath> ();

            for (MidiDeviceInfo info : deviceInfos)
                for (MidiDeviceInfo.PortInfo portInfo : info.getPorts ())
                    if (portInfo.getType () == portType)
                        ports.add (new MidiPortPath (info.getId (), (portType == MidiDeviceInfo.PortInfo.TYPE_INPUT),
                                                     portInfo.getPortNumber ()));

            return ports;
        }

        public MidiPortPath getPortPathForID (int portType, int deviceID)
        {
            for (MidiPortPath port : getAllPorts (portType))
                if (port.hashCode () == deviceID)
                    return port;

            return null;
        }

        private MidiDeviceInfo[] getDeviceInfos ()
        {
            synchronized (MidiDeviceManager.class)
            {
                MidiDeviceInfo[] infos = new MidiDeviceInfo[midiDevices.size ()];

                int idx = 0;
                for (Pair<MidiDevice, BluetoothGatt> midiDevice : midiDevices)
                    infos[idx++] = midiDevice.first.getInfo ();

                return infos;
            }
        }

        private Pair<MidiDevice, BluetoothGatt> getMidiDevicePairForId (int deviceId)
        {
            synchronized (MidiDeviceManager.class)
            {
                for (Pair<MidiDevice, BluetoothGatt> midiDevice : midiDevices)
                    if (midiDevice.first.getInfo ().getId () == deviceId)
                        return midiDevice;
            }

            return null;
        }

        private MidiManager manager;
        private HashMap<String, BluetoothGatt> btDevicesPairing = new HashMap<String, BluetoothGatt>();
        private HashMap<Integer, MidiDeviceOpenTask> openTasks = new HashMap<Integer, MidiDeviceOpenTask>();
        private ArrayList<Pair<MidiDevice, BluetoothGatt>> midiDevices = new ArrayList<Pair<MidiDevice, BluetoothGatt>>();
        private MidiDeviceInfo[] deviceInfos;
        private HashMap<MidiPortPath, WeakReference<JuceMidiPort>> openPorts = new HashMap<MidiPortPath, WeakReference<JuceMidiPort>>();
        private Context appContext = null;
    }

    public static MidiDeviceManager getAndroidMidiDeviceManager (Context context)
    {
        if (context.getSystemService (MIDI_SERVICE) == null)
            return null;

        synchronized (JuceMidiSupport.class)
        {
            if (midiDeviceManager == null)
                midiDeviceManager = new MidiDeviceManager (context);
        }

        return midiDeviceManager;
    }

    public static BluetoothMidiManager getAndroidBluetoothManager (Context context)
    {
        BluetoothAdapter adapter = getDefaultBluetoothAdapter (context);

        if (adapter == null)
            return null;

        if (adapter.getBluetoothLeScanner () == null)
            return null;

        synchronized (JuceMidiSupport.class)
        {
            if (bluetoothManager == null)
                bluetoothManager = new BluetoothMidiManager (context);
        }

        return bluetoothManager;
    }

    // To be called when devices become (un)available
    private native static void handleDevicesChanged();

    private static MidiDeviceManager midiDeviceManager = null;
    private static BluetoothMidiManager bluetoothManager = null;
}
