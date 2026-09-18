package com.deltawaken.adbtile.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trouve le port aléatoire du débogage sans fil de ce téléphone, par mDNS.
 *
 * <p>Nécessaire : sur le Jelly Max, {@code service.adb.tls.port} n'est pas exposée (mesuré le
 * 2026-09-18). Ne retient que le service annoncé depuis une adresse de ce téléphone.
 */
public final class TlsPortFinder {

    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";

    private TlsPortFinder() {
    }

    public static InetSocketAddress find(Context context, long timeoutMs) throws InterruptedException {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        Set<InetAddress> ownAddresses = ownAddresses();
        CountDownLatch found = new CountDownLatch(1);
        AtomicReference<InetSocketAddress> result = new AtomicReference<>();

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo service) {
                nsd.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        InetAddress host = info.getHost();
                        if (host != null && (host.isLoopbackAddress() || ownAddresses.contains(host))) {
                            result.compareAndSet(null, new InetSocketAddress(host, info.getPort()));
                            found.countDown();
                        }
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    }
                });
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                found.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (IllegalArgumentException ignored) {
                // La découverte n'avait pas démarré.
            }
        }
        return result.get();
    }

    private static Set<InetAddress> ownAddresses() {
        Set<InetAddress> addresses = new HashSet<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                addresses.addAll(Collections.list(nif.getInetAddresses()));
            }
        } catch (SocketException | NullPointerException ignored) {
            // Sans liste d'interfaces, seul le bouclage local sera accepté.
        }
        return addresses;
    }
}
