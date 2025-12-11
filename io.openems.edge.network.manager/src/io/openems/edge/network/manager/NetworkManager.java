package io.openems.edge.network.manager;

import io.openems.common.channel.AccessMode;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface NetworkManager extends OpenemsComponent {

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        // 3 Channel hiển thị IP riêng biệt
        LAN1_IP(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("IP of eth0")),
        LAN2_IP(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("IP of eth1")),
        MOBILE_IP(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("IP of 4G")),

        LAST_ERROR_MESSAGE(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY));

        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }
}