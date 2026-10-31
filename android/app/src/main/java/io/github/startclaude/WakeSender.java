package io.github.startclaude;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends Wake-on-LAN packets on the home Wi-Fi.
 *
 * <p>A wake-up packet is a broadcast, so it only reaches the PC from the same network. It is sent on the Wi-Fi
 * itself (not through Tailscale), and never leaves the home network.
 *
 * <p>Android 17 makes apps that target Android 17 ask permission (ACCESS_LOCAL_NETWORK) before talking to devices
 * on the local network. This app targets Android 15, which keeps the access it already has. When targetSdk is
 * raised to 37, add that permission to the manifest and ask for it before the first wake-up.
 */
final class WakeSender {
    private static final int[] PORTS = {9, 7}; // 9 ("discard") is the usual one; some network cards listen on 7

    private final ConnectivityManager connectivity;

    WakeSender(Context context) {
        connectivity = context.getSystemService(ConnectivityManager.class);
    }

    /** A home Wi-Fi network we can send on: the network, and the broadcast address of the subnet it is on. */
    private static final class Target {
        final Network network;
        final byte[] broadcast;

        Target(Network network, byte[] broadcast) {
            this.network = network;
            this.broadcast = broadcast;
        }
    }

    private List<Target> wifiTargets() {
        List<Target> found = new ArrayList<>();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            LinkProperties link = connectivity.getLinkProperties(network);
            if (caps == null || link == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            for (LinkAddress address : link.getLinkAddresses()) {
                InetAddress ip = address.getAddress();
                // Skip Tailscale's own address: it can show up under the Wi-Fi's transport while the VPN is on.
                if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()
                        && !WakeOnLan.isTailnetAddress(ip.getAddress())) {
                    found.add(new Target(network, WakeOnLan.broadcast(ip.getAddress(), address.getPrefixLength())));
                }
            }
        }
        return found;
    }

    /** Whether the phone is on a Wi-Fi network a wake-up packet can go out on. */
    boolean wifiAvailable() {
        return !wifiTargets().isEmpty();
    }

    /** Sends the packet to the Wi-Fi's broadcast address. Returns false if nothing could be sent. */
    boolean send(byte[] mac) {
        byte[] packet = WakeOnLan.magicPacket(mac);
        boolean sent = false;
        for (Target target : wifiTargets()) {
            try (DatagramSocket socket = new DatagramSocket()) {
                target.network.bindSocket(socket); // go out on the Wi-Fi even while a VPN is on
                socket.setBroadcast(true);
                InetAddress subnetBroadcast = InetAddress.getByAddress(target.broadcast);
                InetAddress everyone = InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
                for (int port : PORTS) {
                    socket.send(new DatagramPacket(packet, packet.length, subnetBroadcast, port));
                    socket.send(new DatagramPacket(packet, packet.length, everyone, port));
                }
                sent = true;
            } catch (IOException | SecurityException e) {
                // Try the next network. If every one fails the caller reports it.
            }
        }
        return sent;
    }
}
