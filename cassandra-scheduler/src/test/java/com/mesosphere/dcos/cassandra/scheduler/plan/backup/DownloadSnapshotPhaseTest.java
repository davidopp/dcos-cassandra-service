package com.mesosphere.dcos.cassandra.scheduler.plan.backup;

import com.mesosphere.dcos.cassandra.common.tasks.CassandraDaemonTask;
import com.mesosphere.dcos.cassandra.common.tasks.backup.RestoreContext;
import com.mesosphere.dcos.cassandra.scheduler.offer.ClusterTaskOfferRequirementProvider;
import com.mesosphere.dcos.cassandra.scheduler.tasks.CassandraTasks;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;

public class DownloadSnapshotPhaseTest {
    @Mock
    private ClusterTaskOfferRequirementProvider provider;
    @Mock
    private CassandraTasks cassandraTasks;

    @Before
    public void beforeEach() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCreateBlocksEmpty() {
        final RestoreContext context =  RestoreContext.create("", "", "", "", "", "");

        when(cassandraTasks.getDaemons()).thenReturn(MapUtils.EMPTY_MAP);
        final DownloadSnapshotPhase phase = new DownloadSnapshotPhase(context, cassandraTasks, provider);
        final List<DownloadSnapshotBlock> blocks = phase.createBlocks();

        Assert.assertNotNull(blocks);
        Assert.assertTrue(CollectionUtils.isEmpty(blocks));
        Assert.assertEquals("Download", phase.getName());
    }

    @Test
    public void testCreateBlocksSingle() {
        final RestoreContext context =  RestoreContext.create("", "", "", "", "", "");

        final CassandraDaemonTask daemonTask = Mockito.mock(CassandraDaemonTask.class);
        final HashMap<String, CassandraDaemonTask> map = new HashMap<>();
        map.put("node-0", daemonTask);
        when(cassandraTasks.getDaemons()).thenReturn(map);
        when(cassandraTasks.get("download-node-0")).thenReturn(Optional.of(daemonTask));
        final DownloadSnapshotPhase phase = new DownloadSnapshotPhase(context, cassandraTasks, provider);
        final List<DownloadSnapshotBlock> blocks = phase.createBlocks();

        Assert.assertNotNull(blocks);
        Assert.assertTrue(blocks.size() == 1);

        Assert.assertEquals("download-node-0", blocks.get(0).getName());
    }
}
