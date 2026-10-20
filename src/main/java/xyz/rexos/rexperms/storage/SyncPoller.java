package xyz.rexos.rexperms.storage;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Propagates changes between servers sharing one SQL database.
 *
 * Every change on one server inserts a row into the "messages" table. The
 * other servers poll it every few seconds and apply rows they did not
 * produce themselves. No Redis or message broker required.
 */
public final class SyncPoller {

    public interface Handler {
        void handle(SqlStorage.SyncMessage message);
    }

    private final SqlStorage storage;
    private final String serverId = UUID.randomUUID().toString();
    private final Handler handler;
    private final Logger logger;
    private long lastSeenId;
    private int pollsSinceCleanup;

    public SyncPoller(SqlStorage storage, Handler handler, Logger logger) {
        this.storage = storage;
        this.handler = handler;
        this.logger = logger;
    }

    public void start() throws SQLException {
        lastSeenId = storage.latestMessageId();
    }

    public void publish(String type, String target) {
        try {
            storage.pushMessage(serverId, type, target);
        } catch (SQLException e) {
            logger.warning("Could not publish sync message: " + e.getMessage());
        }
    }

    /** Called on a storage thread. */
    public void poll() {
        try {
            List<SqlStorage.SyncMessage> messages = storage.pollMessages(lastSeenId);
            for (SqlStorage.SyncMessage m : messages) {
                lastSeenId = Math.max(lastSeenId, m.id());
                if (!serverId.equals(m.origin())) handler.handle(m);
            }
            if (++pollsSinceCleanup >= 600) {
                pollsSinceCleanup = 0;
                storage.cleanupMessages();
            }
        } catch (SQLException e) {
            logger.warning("Sync poll failed: " + e.getMessage());
        }
    }
}
