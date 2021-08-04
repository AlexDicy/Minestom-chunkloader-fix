package net.minestom.server.network.socket;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.network.PacketProcessor;
import net.minestom.server.network.player.NettyPlayerConnection;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.Inflater;

@ApiStatus.Internal
public final class Worker {
    final Selector selector = Selector.open();
    private final Map<SocketChannel, NettyPlayerConnection> connectionMap = new ConcurrentHashMap<>();
    private final PacketProcessor packetProcessor;

    public Worker(Server server, PacketProcessor packetProcessor) throws IOException {
        this.packetProcessor = packetProcessor;
        Thread.start(server, this::threadTick);
    }

    private void threadTick(Context workerContext) {
        try {
            selector.select();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        for (SelectionKey key : selectedKeys) {
            SocketChannel channel = (SocketChannel) key.channel();
            if (!channel.isOpen()) {
                continue;
            }
            if (!key.isReadable()) {
                // We only care about read
                continue;
            }
            var connection = connectionMap.get(channel);
            try {
                ByteBuffer readBuffer = workerContext.readBuffer;
                // Consume last incomplete packet
                connection.consumeCache(readBuffer);
                // Read socket
                if (channel.read(readBuffer) == -1) {
                    // EOS
                    throw new IOException("Disconnected");
                }
                // Process data
                readBuffer.flip();
                connection.processPackets(workerContext, packetProcessor);
            } catch (IOException e) {
                // TODO print exception? (should ignore disconnection)
                try {
                    disconnect(connection, channel);
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            } finally {
                workerContext.clearBuffers();
            }
        }
        selectedKeys.clear();
    }

    public void receiveConnection(SocketChannel channel) throws IOException {
        this.connectionMap.put(channel, new NettyPlayerConnection(this, channel, channel.getRemoteAddress()));
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ);
        var socket = channel.socket();
        socket.setSendBufferSize(Server.SOCKET_BUFFER_SIZE);
        socket.setReceiveBufferSize(Server.SOCKET_BUFFER_SIZE);
        socket.setTcpNoDelay(Server.NO_DELAY);
        this.selector.wakeup();
    }

    public void disconnect(NettyPlayerConnection connection, SocketChannel channel) throws IOException {
        // Client close
        connection.refreshOnline(false);
        channel.close();
        this.connectionMap.remove(channel);
        // Remove the connection
        connection.refreshOnline(false);
        Player player = connection.getPlayer();
        if (player != null) {
            player.remove();
            MinecraftServer.getConnectionManager().removePlayer(connection);
        }
    }

    /**
     * Thread responsible for reading players socket and forwarding packets into
     * players' packet queue.
     */
    static final class Thread extends java.lang.Thread {
        private static final AtomicInteger COUNTER = new AtomicInteger();

        private Thread(Runnable runnable) {
            super(null, runnable, "Ms-worker-" + COUNTER.getAndIncrement());
        }

        static void start(Server server, Consumer<Context> runnable) {
            new Thread(() -> {
                Context context = new Context();
                while (server.isOpen()) {
                    try {
                        runnable.accept(context);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    /**
     * Contains objects that we can be shared across all the connection of a {@link Worker worker}.
     */
    public static final class Context {
        public final ByteBuffer readBuffer = ByteBuffer.allocateDirect(Server.SOCKET_BUFFER_SIZE);
        /**
         * Stores a single packet payload to be read.
         */
        public final ByteBuffer contentBuffer = ByteBuffer.allocateDirect(Server.MAX_PACKET_SIZE);
        public final Inflater inflater = new Inflater();

        public void clearBuffers() {
            this.readBuffer.clear();
            this.contentBuffer.clear();
        }
    }
}
