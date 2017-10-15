/*
 * :vi ts=4 sts=4 sw=4
 *
 * Copyright (c) Jonathan Burchmore
 */

package com.burchmore.andalfa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.view.Display;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

public class AndAlfa extends Activity implements SensorEventListener
{
	TextView			m_tv;
	float[]				m_geomagnetic;
	float[]				m_gravity;
	double				m_pitch;
	double				m_roll;
	Display				m_display;
	SensorManager		m_sensormanager;
	Sensor				m_accelerometer;
	Sensor				m_magnetometer;
	Timer				m_timer;
	BluetoothAdapter	m_btadapter;
	BluetoothSocket		m_btsocket;
	OutputStream		m_btout;
	InputStream			m_btin;
	static final UUID	m_btserial_uuid = UUID.fromString( "00001101-0000-1000-8000-00805F9B34FB" );
	static String		m_btserial_addr = "00:11:12:12:13:60";
	static int			DIRECT_INPUT_FOLLOWS = 0xFF;
	int					m_last_throttle = 0, m_last_steering = 0;
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        
        m_tv			= new TextView( this );      
        m_display		= ( ( WindowManager ) getSystemService( WINDOW_SERVICE ) ).getDefaultDisplay();
        m_sensormanager	= ( SensorManager ) getSystemService( SENSOR_SERVICE );
        m_accelerometer	= m_sensormanager.getDefaultSensor( Sensor.TYPE_ACCELEROMETER );
        m_magnetometer	= m_sensormanager.getDefaultSensor( Sensor.TYPE_MAGNETIC_FIELD );
        
        m_timer			= new Timer();
        m_timer.schedule( new TimerTask() { public void run() { onTimer(); } }, 0, 10 );
        
        m_btadapter		= BluetoothAdapter.getDefaultAdapter();

        m_tv.setKeepScreenOn( true );
        setContentView( m_tv );
    }

    protected void onResume()
    {
    	BluetoothDevice btdevice;

    	m_btsocket	= null;
    	m_btout		= null;
    	m_btin		= null;
    	
    	super.onResume();
    	
    	m_sensormanager.registerListener( this, m_accelerometer, SensorManager.SENSOR_DELAY_UI );
    	m_sensormanager.registerListener( this, m_magnetometer, SensorManager.SENSOR_DELAY_UI );
    
    	if ( ( btdevice = m_btadapter.getRemoteDevice( m_btserial_addr ) ) == null )
    	{
    		return;
    	}
    	
    	try
    	{
    		m_btsocket	= btdevice.createRfcommSocketToServiceRecord( m_btserial_uuid );
        	m_btadapter.cancelDiscovery();
    		m_btsocket.connect();
    		m_btout		= m_btsocket.getOutputStream();
    		m_btin		= m_btsocket.getInputStream();
    	}
    	catch ( IOException e )
    	{
    	}
    }

    protected void onPause()
    {
    	super.onPause();
    	
    	m_sensormanager.unregisterListener( this );
    	
		try
		{
	    	if ( m_btout != null )
	    	{
	    		m_btout.write( DIRECT_INPUT_FOLLOWS );
	    		m_btout.write( 128 );
	    		m_btout.write( 128 );
	    		
	    		m_btout.flush();
	    	}
	    	
	    	if ( m_btin != null )		m_btin.close();
	    	if ( m_btsocket != null )	m_btsocket.close();
		}
		catch ( IOException e )
		{
		}
		
		m_btout		= null;
		m_btin		= null;
		m_btsocket	= null;
    }
    
    // SensorEventListener
    public void onAccuracyChanged( Sensor sensor, int accuracy )
    {
    }
    
    public void onSensorChanged( SensorEvent event )
    {
    	switch ( event.sensor.getType() )
    	{
    		case Sensor.TYPE_ACCELEROMETER :
    		{
    			m_gravity		= event.values;
    			break;
    		}
    		case Sensor.TYPE_MAGNETIC_FIELD :
    		{
    			m_geomagnetic	= event.values;
    			break;
    		}
    	}
    	
    	UpdateOrientation();
    }
    
    public void UpdateOrientation()
    {
    	float[] matrix;
    	float[] orientation;
    	double new_pitch, new_roll;
    	
    	if ( m_gravity == null || m_geomagnetic == null )
    	{
    		return;
    	}
    	
    	matrix		= new float[ 9 ];
    	
    	if ( !SensorManager.getRotationMatrix( matrix, null, m_gravity, m_geomagnetic ) )
    	{
    		return;
    	}
    	
    	orientation	= new float[ 3 ];
    	SensorManager.getOrientation( matrix, orientation );
    	
    	new_pitch	= Math.toDegrees( orientation[ 1 ] );
    	new_roll	= Math.toDegrees( orientation[ 2 ] );
    	
    	switch ( m_display.getOrientation() )
    	{
    		case Surface.ROTATION_90 :
    		{
    			m_pitch	= new_roll + 90;
    			m_roll	= new_pitch;

    			break;
    		}
    		case Surface.ROTATION_270 :
    		{
    			m_pitch	= 90 - new_roll;
    			m_roll	= -new_pitch;

    			break;
    		}
    		default :
    		{
    			m_pitch	= 0;
    			m_roll	= 0;
    		}
    	}
    	
    	if ( m_pitch < -45 )		m_pitch = -45;
    	else if ( m_pitch > 45 )	m_pitch = 45;
    	
    	if ( m_roll < -45 )			m_roll	= -45;
    	else if ( m_roll > 45 )		m_roll	= 45;
    }
    
    private void onTimer()
    {
    	this.runOnUiThread( onTimerInUI );
    }
    
    private Runnable onTimerInUI = new Runnable()
    {
    	public void run()
    	{
    		int input_bytes;
    		int throttle, steering;
    		
    		if ( m_btout == null || m_btin == null )
    		{
    			m_tv.setText( "Not connected to Arduino" );
    			return;
    		}
    		
        	throttle	= ( int ) ( ( ( m_pitch + 45 ) / 90.0 ) * 255.0 );
        	steering	= ( int ) ( ( ( m_roll + 45 ) / 90.0 ) * 255.0 );

        	m_tv.setText( "pitch=" + m_pitch + " roll=" + m_roll + " throttle=" + throttle + " steering=" + steering );
        	
        	if ( throttle == m_last_throttle && steering == m_last_steering )
        	{
        		return;
        	}
        	
        	try
        	{
        		if ( ( input_bytes = m_btin.available() ) > 0 )
        		{
        			m_btin.skip( input_bytes );
        		}

        		m_btout.write( DIRECT_INPUT_FOLLOWS );
        		m_btout.write( throttle );
        		m_btout.write( steering );
        		m_btout.flush();
        	}
        	catch ( IOException e )
        	{
        		Log.e( "AndAlfa", "write failed", e );
        	}
        	
        	m_last_throttle	= throttle;
        	m_last_steering	= steering;
    	}
    };
}
