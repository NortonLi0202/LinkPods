package local.airpods.handoffprobe;

import java.util.Collections;
import java.util.List;

/** Immutable domain status summary shared by the app home UI and persistent notification. */
public final class StatusSummary {
    public enum Level {
        NORMAL("正常"),
        ATTENTION("需注意"),
        UNAVAILABLE("不可用");

        public final String label;

        Level(String label) {
            this.label = label;
        }
    }

    public enum PrimaryAction {
        NONE(""),
        GRANT_BLUETOOTH_PERMISSION("授予附近设备权限"),
        SELECT_DEVICE("选择耳机"),
        START_CONTROL("连接耳机"),
        ENABLE_HANDOFF("启用自动交接"),
        PREPARE_BRIDGE("打开应用准备桥接"),
        GRANT_NOTIFICATION_ACCESS("授予媒体监听权限"),
        MANUAL_HANDOFF("手动切换"),
        OPEN_APP("查看状态");

        public final String label;

        PrimaryAction(String label) {
            this.label = label;
        }
    }

    public static final class NotificationAction {
        public enum Kind {
            LISTENING_MODE,
            PRIMARY_ACTION
        }

        public final Kind kind;
        public final String label;
        public final int listeningMode;
        public final PrimaryAction primaryAction;

        private NotificationAction(Kind kind, String label, int listeningMode, PrimaryAction primaryAction) {
            this.kind = kind;
            this.label = label == null ? "" : label;
            this.listeningMode = listeningMode;
            this.primaryAction = primaryAction == null ? PrimaryAction.NONE : primaryAction;
        }

        public static NotificationAction mode(String label, int listeningMode) {
            return new NotificationAction(Kind.LISTENING_MODE, label, listeningMode, PrimaryAction.NONE);
        }

        public static NotificationAction primary(PrimaryAction action) {
            return new NotificationAction(Kind.PRIMARY_ACTION, action.label, -1, action);
        }
    }

    public final Level level;
    public final String headline;
    public final String detail;
    public final PrimaryAction primaryAction;

    public StatusSummary(Level level, String headline, String detail, PrimaryAction primaryAction) {
        this.level = level == null ? Level.UNAVAILABLE : level;
        this.headline = headline == null ? "" : headline;
        this.detail = detail == null ? "" : detail;
        this.primaryAction = primaryAction == null ? PrimaryAction.NONE : primaryAction;
    }

    public boolean hasPrimaryAction() {
        return primaryAction != PrimaryAction.NONE;
    }

    public String primaryActionLabel() {
        return primaryAction.label;
    }

    public String levelLabel() {
        return level.label;
    }

    public static List<NotificationAction> emptyActions() {
        return Collections.emptyList();
    }
}
