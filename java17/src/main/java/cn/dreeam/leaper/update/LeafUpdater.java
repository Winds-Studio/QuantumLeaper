package cn.dreeam.leaper.update;

public final class LeafUpdater extends AbstractUpdater {

    public LeafUpdater() {
        super(
                ApiType.BIBLIOTHEK,
                "https://api.leafmc.one/v2/projects/leaf"
        );
    }
}
