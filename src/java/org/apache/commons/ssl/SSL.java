/*
 * $Header$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 *
 *  Copyright 2006 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.commons.ssl;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * @author Credit Union Central of British Columbia
 * @author <a href="http://www.cucbc.com/">www.cucbc.com</a>
 * @author <a href="mailto:juliusdavies@cucbc.com">juliusdavies@cucbc.com</a>
 * @since May 1, 2006
 */
public class SSL
{
	private final static String[] KNOWN_PROTOCOLS =
			{ "TLSv1", "SSLv3", "SSLv2", "SSLv2Hello" };

	// SUPPORTED_CIPHERS_ARRAY is initialized in the static constructor.
	private final static String[] SUPPORTED_CIPHERS;

	public final static SortedSet KNOWN_PROTOCOLS_SET;
	public final static SortedSet SUPPORTED_CIPHERS_SET;

	static
	{
		TreeSet ts = new TreeSet( Collections.reverseOrder() );
		ts.addAll( Arrays.asList( KNOWN_PROTOCOLS ) );
		KNOWN_PROTOCOLS_SET = Collections.unmodifiableSortedSet( ts );

		SSLSocketFactory s = (SSLSocketFactory) SSLSocketFactory.getDefault();
		ts = new TreeSet();
		SUPPORTED_CIPHERS = s.getSupportedCipherSuites();
		ts.addAll( Arrays.asList( SUPPORTED_CIPHERS ) );
		SUPPORTED_CIPHERS_SET = Collections.unmodifiableSortedSet( ts );
	}

	private Object sslContext = null;
	private int initCount = 0;
	private SSLSocketFactory socketFactory = null;
	private SSLServerSocketFactory serverSocketFactory = null;
	private boolean doVerify = true;
	private boolean checkCRL = true;
	private boolean useClientMode = false;
	private boolean useClientModeDefault = true;
	private int soTimeout = 24 * 60 * 60 * 1000; // default: one day
	private int connectTimeout = 60 * 60 * 1000; // default: one hour
	private TrustChain trustChain = null;
	private KeyMaterial keyMaterial = null;
	private String[] enabledCiphers = null;
	private String[] enabledProtocols = null;
	private String defaultProtocol = "TLS";
	private X509Certificate[] currentServerChain;
	private X509Certificate[] currentClientChain;
	private boolean wantClientAuth = true;
	private boolean needClientAuth = false;
	private SSLWrapperFactory sslWrapperFactory =
			DefaultSSLWrapperFactory.getInstance();

	public SSL()
			throws NoSuchAlgorithmException, KeyStoreException,
			       KeyManagementException, IOException, CertificateException
	{
		dirtyAndReloadIfYoung();
	}

	private synchronized void dirty()
	{
		this.sslContext = null;
		this.socketFactory = null;
		this.serverSocketFactory = null;
	}

	private synchronized void dirtyAndReloadIfYoung()
			throws NoSuchAlgorithmException, KeyStoreException,
			       KeyManagementException, IOException, CertificateException
	{
		dirty();
		if ( initCount >= 0 && initCount <= 5 )
		{
			// The first five init's we do early (before any sockets are
			// created) in the hope that will trigger any explosions nice
			// and early, with the correct exception type.

			// After the first five init's, we revert to a regular
			// dirty / init pattern, and the "init" happens very late:
			// just before the socket is created.  If badness happens, a
			// wrapping RuntimeException will be thrown.
			init();
		}
	}

	public synchronized void addTrustMaterial( TrustChain trustChain )
			throws NoSuchAlgorithmException, KeyStoreException,
			       KeyManagementException, IOException, CertificateException
	{
		if ( this.trustChain == null || trustChain == TrustMaterial.TRUST_ALL )
		{
			this.trustChain = trustChain;
		}
		else
		{
			this.trustChain.addTrustMaterial( trustChain );
		}
		dirtyAndReloadIfYoung();
	}

	public synchronized void setTrustMaterial( TrustChain trustChain )
			throws NoSuchAlgorithmException, KeyStoreException,
			       KeyManagementException, IOException, CertificateException
	{
		this.trustChain = trustChain;
		dirtyAndReloadIfYoung();
	}

	public synchronized void setKeyMaterial( KeyMaterial keyMaterial )
			throws NoSuchAlgorithmException, KeyStoreException,
			       KeyManagementException, IOException, CertificateException
	{
		this.keyMaterial = keyMaterial;
		dirtyAndReloadIfYoung();
	}

	public synchronized X509Certificate[] getAssociatedCertificateChain()
	{
		if ( keyMaterial != null )
		{
			return keyMaterial.getAssociatedCertificateChain();
		}
		else
		{
			return null;
		}
	}

	public synchronized String[] getEnabledCiphers()
	{
		return enabledCiphers != null ? enabledCiphers : getDefaultCipherSuites();
	}

	public synchronized void setEnabledCiphers( String[] ciphers )
	{
		HashSet desired = new HashSet( Arrays.asList( ciphers ) );
		desired.removeAll( SUPPORTED_CIPHERS_SET );
		if ( !desired.isEmpty() )
		{
			throw new IllegalArgumentException( "following ciphers not supported: " + desired );
		}
		this.enabledCiphers = ciphers;
		dirty();
	}

	public synchronized String[] getEnabledProtocols()
	{
		return enabledProtocols != null ? enabledProtocols : KNOWN_PROTOCOLS;
	}

	public synchronized void setEnabledProtocols( String[] protocols )
	{
		HashSet desired = new HashSet( Arrays.asList( protocols ) );
		desired.removeAll( KNOWN_PROTOCOLS_SET );
		if ( !desired.isEmpty() )
		{
			throw new IllegalArgumentException( "following protocols not supported: " + desired );
		}
		this.enabledProtocols = protocols;
		dirty();
	}

	public synchronized String getDefaultProtocol()
	{
		return defaultProtocol;
	}

	public synchronized void setDefaultProtocol( String protocol )
	{
		this.defaultProtocol = protocol;
		dirty();
	}

	public boolean getDoVerify()
	{
		return doVerify;
	}

	public synchronized void setDoVerify( boolean doVerify )
	{
		this.doVerify = doVerify;
	}

	public boolean getCheckCRL()
	{
		return checkCRL;
	}

	public void setCheckCRL( boolean checkCRL )
	{
		this.checkCRL = checkCRL;
	}

	public void setSoTimeout( int soTimeout )
	{
		if ( soTimeout < 0 )
		{
			throw new IllegalArgumentException( "soTimeout must not be negative" );
		}
		this.soTimeout = soTimeout;
	}

	public void setConnectTimeout( int connectTimeout )
	{
		if ( connectTimeout < 0 )
		{
			throw new IllegalArgumentException( "connectTimeout must not be negative" );
		}
		this.connectTimeout = connectTimeout;
	}

	public void setUseClientMode( boolean useClientMode )
	{
		this.useClientModeDefault = false;
		this.useClientMode = useClientMode;
	}

	public void setWantClientAuth( boolean wantClientAuth )
	{
		this.wantClientAuth = wantClientAuth;
	}

	public void setNeedClientAuth( boolean needClientAuth )
	{
		this.needClientAuth = needClientAuth;
	}

	public SSLWrapperFactory getSSLWrapperFactory()
	{
		return this.sslWrapperFactory;
	}

	public void setSSLWrapperFactory( SSLWrapperFactory wf )
	{
		this.sslWrapperFactory = wf;
	}


	private synchronized void initThrowRuntime()
	{
		try
		{
			init();
		}
		catch ( GeneralSecurityException gse )
		{
			throw JavaImpl.newRuntimeException( gse );
		}
		catch ( IOException ioe )
		{
			throw JavaImpl.newRuntimeException( ioe );
		}
	}

	private synchronized void init()
			throws NoSuchAlgorithmException, KeyStoreException,
			       KeyManagementException, IOException, CertificateException
	{
		socketFactory = null;
		serverSocketFactory = null;
		this.sslContext = JavaImpl.init( this, trustChain, keyMaterial );
		initCount++;
	}

	public void doPreConnectSocketStuff( SSLSocket s )
			throws IOException
	{
		if ( !useClientModeDefault )
		{
			s.setUseClientMode( useClientMode );
		}
		if ( soTimeout > 0 )
		{
			s.setSoTimeout( soTimeout );
		}
		if ( enabledProtocols != null )
		{
			JavaImpl.setEnabledProtocols( s, enabledProtocols );
		}
		if ( enabledCiphers != null )
		{
			s.setEnabledCipherSuites( enabledCiphers );
		}
	}

	public void doPostConnectSocketStuff( Socket s, String host )
			throws IOException
	{
		if ( doVerify )
		{
			Util.verifyHostName( host, s );
		}
	}

	public SSLSocket createSocket() throws IOException
	{
		return sslWrapperFactory.wrap( JavaImpl.createSocket( this ) );
	}

	public Socket createSocket( InetAddress host, int port )
			throws IOException
	{
		return createSocket( host.getHostName(), port );
	}

	public Socket createSocket( String host, int port )
			throws IOException
	{
		return createSocket( host, port, null, 0 );
	}

	public Socket createSocket( InetAddress host, int port,
	                            InetAddress localHost, int localPort )
			throws IOException
	{
		return createSocket( host.getHostName(), port, localHost, localPort );
	}

	public Socket createSocket( String remoteHost, int remotePort,
	                            InetAddress localHost, int localPort )
			throws IOException
	{
		return createSocket( remoteHost, remotePort, localHost, localPort, 0 );
	}

	/**
	 * Attempts to get a new socket connection to the given host within the
	 * given time limit.
	 *
	 * @param remoteHost the host name/IP
	 * @param remotePort the port on the host
	 * @param localHost  the local host name/IP to bind the socket to
	 * @param localPort  the port on the local machine
	 * @param timeout    the connection timeout (0==infinite)
	 * @return Socket a new socket
	 * @throws IOException          if an I/O error occurs while creating the socket
	 * @throws UnknownHostException if the IP address of the host cannot be
	 *                              determined
	 */
	public Socket createSocket( String remoteHost, int remotePort,
	                            InetAddress localHost, int localPort,
	                            int timeout )
			throws IOException
	{
		// Only use our factory-wide connectTimeout if this method was passed
		// in a timeout of 0 (infinite).
		int factoryTimeout = getConnectTimeout();
		int connectTimeout = timeout == 0 ? factoryTimeout : timeout;
		Socket s = JavaImpl.createSocket( this, remoteHost, remotePort,
		                                  localHost, localPort, connectTimeout );
		return sslWrapperFactory.wrap( (SSLSocket) s );
		// return s;
	}

	public Socket createSocket( Socket s, String remoteHost, int remotePort,
	                            boolean autoClose )
			throws IOException
	{
		SSLSocketFactory sf = getSSLSocketFactory();
		SSLSocket ss = (SSLSocket) sf.createSocket( s, remoteHost, remotePort,
		                                            autoClose );
		doPreConnectSocketStuff( ss );
		doPostConnectSocketStuff( ss, remoteHost );
		return sslWrapperFactory.wrap( ss );
		// return ss;
	}

	public void doPreConnectServerSocketStuff( SSLServerSocket s )
			throws IOException
	{
		if ( soTimeout > 0 )
		{
			s.setSoTimeout( soTimeout );
		}
		if ( enabledProtocols != null )
		{
			JavaImpl.setEnabledProtocols( s, enabledProtocols );
		}
		if ( enabledCiphers != null )
		{
			s.setEnabledCipherSuites( enabledCiphers );
		}

		/*
		setNeedClientAuth( false ) has an annoying side effect:  it seems to
		reset setWantClient( true ) back to to false.  So I do things this
		way to make sure setting things "true" happens after setting things
		"false" - giving "true" priority.
		*/
		if ( !wantClientAuth )
		{
			JavaImpl.setWantClientAuth( s, wantClientAuth );
		}
		if ( !needClientAuth )
		{
			s.setNeedClientAuth( needClientAuth );
		}
		if ( wantClientAuth )
		{
			JavaImpl.setWantClientAuth( s, wantClientAuth );
		}
		if ( needClientAuth )
		{
			s.setNeedClientAuth( needClientAuth );
		}
	}

	public synchronized SSLSocketFactory getSSLSocketFactory()
	{
		if ( sslContext == null )
		{
			initThrowRuntime();
		}
		if ( socketFactory == null )
		{
			socketFactory = JavaImpl.getSSLSocketFactory( sslContext );
		}
		return socketFactory;
	}

	public synchronized SSLServerSocketFactory getSSLServerSocketFactory()
	{
		if ( sslContext == null )
		{
			initThrowRuntime();
		}
		if ( serverSocketFactory == null )
		{
			serverSocketFactory = JavaImpl.getSSLServerSocketFactory( sslContext );
		}
		return serverSocketFactory;
	}

	public int getConnectTimeout()
	{
		return connectTimeout;
	}

	public String[] getDefaultCipherSuites()
	{
		return getSSLSocketFactory().getDefaultCipherSuites();
	}

	public String[] getSupportedCipherSuites()
	{
		String[] s = new String[SUPPORTED_CIPHERS.length];
		System.arraycopy( SUPPORTED_CIPHERS, 0, s, 0, s.length );
		return s;
	}

	public TrustChain getTrustChain()
	{
		return trustChain;
	}

	public void setCurrentServerChain( X509Certificate[] chain )
	{
		this.currentServerChain = chain;
	}

	public void setCurrentClientChain( X509Certificate[] chain )
	{
		this.currentClientChain = chain;
	}

	public X509Certificate[] getCurrentServerChain()
	{
		return currentServerChain;
	}

	public X509Certificate[] getCurrentClientChain()
	{
		return currentClientChain;
	}


}
