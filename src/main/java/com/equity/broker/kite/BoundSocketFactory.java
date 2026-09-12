package com.equity.broker.kite;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.SocketFactory;

/**
 * A socket factory whose every outbound connection leaves from one chosen local address.
 *
 * <h2>Why a trading engine needs to pick its own source address</h2>
 * <p>SEBI's static-IP rule for retail algorithmic trading requires every broker API key to be
 * registered against <b>one</b> public IP, and the broker refuses orders that arrive from anywhere
 * else. One user on one machine satisfies that by accident. Several users on one machine do not:
 * each holds their own key, each key is registered to a different address, and yet every call would
 * leave from the box's single default interface — so at most one of them could ever trade.</p>
 *
 * <p>The way out is to give the machine several addresses and have each user's traffic leave from
 * theirs. On AWS that is a secondary private IP on the network interface, mapped to its own Elastic
 * IP; in the JDK it is {@link Socket#bind} to that private address before {@link Socket#connect}.
 * This class is the second half. Handed to OkHttp as its {@code socketFactory}, it binds every
 * socket that client opens — REST calls and the ticker WebSocket alike — so a client built for a
 * user is a client that can only ever speak from that user's address.</p>
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It does not validate that the address is on this machine. A bind to an address the OS does not
 * hold fails at connect time with a clear {@link java.net.BindException}, which is the right place
 * to fail: loudly, per call, and attributable to the user whose configuration is wrong — rather than
 * silently falling back to the default interface and letting an order go out from an address the
 * broker will reject.</p>
 */
final class BoundSocketFactory extends SocketFactory {

    private final InetAddress localAddress;

    BoundSocketFactory(InetAddress localAddress) {
        if (localAddress == null) throw new IllegalArgumentException("local address required");
        this.localAddress = localAddress;
    }

    InetAddress localAddress() { return localAddress; }

    @Override
    public Socket createSocket() throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, 0));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress ignoredLocalHost, int localPort)
            throws IOException {
        // The caller's idea of a local host is overridden on purpose: the whole point of this
        // factory is that the local address is not negotiable per call.
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress ignoredLocalHost, int localPort)
            throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }
}
