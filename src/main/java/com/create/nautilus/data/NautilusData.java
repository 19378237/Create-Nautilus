package com.create.nautilus.data;

import com.create.nautilus.api.PhysicallySimulatedEntity;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/** Attachment 的统一访问入口。 */
public final class NautilusData {
    private NautilusData() {
    }

    public static NautilusLevelData levelData(final ServerSubLevel carrier) {
        return carrier.getLevel().getData(NautilusAttachments.SUBMARINE_DATA.get());
    }

    public static CarrierState carrier(final ServerSubLevel carrier) {
        return levelData(carrier).carrier(carrier.getUniqueId());
    }

    public static CarrierState carrier(final PhysicallySimulatedEntity carrier) {
        return carrier(carrier.subLevel());
    }
}

