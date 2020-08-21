/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.dataframe;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.index.IndexAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.persistent.UpdatePersistentTaskStatusAction;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.ml.action.StartDataFrameAnalyticsAction;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsState;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsTaskState;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.core.ml.utils.PhaseProgress;
import org.elasticsearch.xpack.ml.dataframe.DataFrameAnalyticsTask.StartingState;
import org.elasticsearch.xpack.ml.dataframe.stats.ProgressTracker;
import org.elasticsearch.xpack.ml.notifications.DataFrameAnalyticsAuditor;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class DataFrameAnalyticsTaskTests extends ESTestCase {

    public void testDetermineStartingState_GivenZeroProgress() {
        List<PhaseProgress> progress = Arrays.asList(new PhaseProgress("reindexing", 0),
            new PhaseProgress("loading_data", 0),
            new PhaseProgress("analyzing", 0),
            new PhaseProgress("writing_results", 0));

        StartingState startingState = DataFrameAnalyticsTask.determineStartingState("foo", progress);

        assertThat(startingState, equalTo(StartingState.FIRST_TIME));
    }

    public void testDetermineStartingState_GivenReindexingIsIncomplete() {
        List<PhaseProgress> progress = Arrays.asList(new PhaseProgress("reindexing", 99),
            new PhaseProgress("loading_data", 0),
            new PhaseProgress("analyzing", 0),
            new PhaseProgress("writing_results", 0));

        StartingState startingState = DataFrameAnalyticsTask.determineStartingState("foo", progress);

        assertThat(startingState, equalTo(StartingState.RESUMING_REINDEXING));
    }

    public void testDetermineStartingState_GivenLoadingDataIsIncomplete() {
        List<PhaseProgress> progress = Arrays.asList(new PhaseProgress("reindexing", 100),
            new PhaseProgress("loading_data", 1),
            new PhaseProgress("analyzing", 0),
            new PhaseProgress("writing_results", 0));

        StartingState startingState = DataFrameAnalyticsTask.determineStartingState("foo", progress);

        assertThat(startingState, equalTo(StartingState.RESUMING_ANALYZING));
    }

    public void testDetermineStartingState_GivenAnalyzingIsIncomplete() {
        List<PhaseProgress> progress = Arrays.asList(new PhaseProgress("reindexing", 100),
            new PhaseProgress("loading_data", 100),
            new PhaseProgress("analyzing", 99),
            new PhaseProgress("writing_results", 0));

        StartingState startingState = DataFrameAnalyticsTask.determineStartingState("foo", progress);

        assertThat(startingState, equalTo(StartingState.RESUMING_ANALYZING));
    }

    public void testDetermineStartingState_GivenWritingResultsIsIncomplete() {
        List<PhaseProgress> progress = Arrays.asList(new PhaseProgress("reindexing", 100),
            new PhaseProgress("loading_data", 100),
            new PhaseProgress("analyzing", 100),
            new PhaseProgress("writing_results", 1));

        StartingState startingState = DataFrameAnalyticsTask.determineStartingState("foo", progress);

        assertThat(startingState, equalTo(StartingState.RESUMING_ANALYZING));
    }

    public void testDetermineStartingState_GivenFinished() {
        List<PhaseProgress> progress = Arrays.asList(new PhaseProgress("reindexing", 100),
            new PhaseProgress("loading_data", 100),
            new PhaseProgress("analyzing", 100),
            new PhaseProgress("writing_results", 100));

        StartingState startingState = DataFrameAnalyticsTask.determineStartingState("foo", progress);

        assertThat(startingState, equalTo(StartingState.FINISHED));
    }

    public void testDetermineStartingState_GivenEmptyProgress() {
        StartingState startingState = DataFrameAnalyticsTask.determineStartingState("foo", Collections.emptyList());
        assertThat(startingState, equalTo(StartingState.FINISHED));
    }

    private void testPersistProgress(SearchHits searchHits, String expectedIndexOrAlias) throws IOException {
        Client client = mock(Client.class);
        when(client.settings()).thenReturn(Settings.EMPTY);
        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(client.threadPool()).thenReturn(threadPool);

        ClusterService clusterService = mock(ClusterService.class);
        DataFrameAnalyticsManager analyticsManager = mock(DataFrameAnalyticsManager.class);
        DataFrameAnalyticsAuditor auditor = mock(DataFrameAnalyticsAuditor.class);
        PersistentTasksService persistentTasksService = new PersistentTasksService(clusterService, threadPool, client);

        List<PhaseProgress> progress = Arrays.asList(
            new PhaseProgress(ProgressTracker.REINDEXING, 100),
            new PhaseProgress(ProgressTracker.LOADING_DATA, 50),
            new PhaseProgress(ProgressTracker.WRITING_RESULTS, 0));

        StartDataFrameAnalyticsAction.TaskParams taskParams = new StartDataFrameAnalyticsAction.TaskParams(
            "task_id", Version.CURRENT, progress, false);

        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.getHits()).thenReturn(searchHits);
        doAnswer(withResponse(searchResponse)).when(client).execute(eq(SearchAction.INSTANCE), any(), any());

        IndexResponse indexResponse = mock(IndexResponse.class);
        doAnswer(withResponse(indexResponse)).when(client).execute(eq(IndexAction.INSTANCE), any(), any());

        TaskManager taskManager = mock(TaskManager.class);

        Runnable runnable = mock(Runnable.class);

        DataFrameAnalyticsTask task =
            new DataFrameAnalyticsTask(
                123, "type", "action", null, Collections.emptyMap(), client, clusterService, analyticsManager, auditor, taskParams);
        task.init(persistentTasksService, taskManager, "task-id", 42);

        task.persistProgress(client, "task_id", runnable);

        ArgumentCaptor<IndexRequest> indexRequestCaptor = ArgumentCaptor.forClass(IndexRequest.class);

        InOrder inOrder = inOrder(client, runnable);
        inOrder.verify(client).execute(eq(SearchAction.INSTANCE), any(), any());
        inOrder.verify(client).execute(eq(IndexAction.INSTANCE), indexRequestCaptor.capture(), any());
        inOrder.verify(runnable).run();
        inOrder.verifyNoMoreInteractions();

        IndexRequest indexRequest = indexRequestCaptor.getValue();
        assertThat(indexRequest.index(), equalTo(expectedIndexOrAlias));
        assertThat(indexRequest.id(), equalTo("data_frame_analytics-task_id-progress"));

        try (XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY, DeprecationHandler.IGNORE_DEPRECATIONS, indexRequest.source().utf8ToString())) {
            StoredProgress parsedProgress = StoredProgress.PARSER.apply(parser, null);
            assertThat(parsedProgress.get(), equalTo(progress));
        }
    }

    public void testPersistProgress_ProgressDocumentCreated() throws IOException {
        testPersistProgress(SearchHits.empty(), ".ml-state-write");
    }

    public void testPersistProgress_ProgressDocumentUpdated() throws IOException {
        testPersistProgress(
            new SearchHits(new SearchHit[]{ SearchHit.createFromMap(Collections.singletonMap("_index", ".ml-state-dummy")) }, null, 0.0f),
            ".ml-state-dummy");
    }

    public void testSetFailed() throws IOException {
        testSetFailed(false);
    }

    public void testSetFailedDuringNodeShutdown() throws IOException {
        testSetFailed(true);
    }

    private void testSetFailed(boolean nodeShuttingDown) throws IOException {
        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        Client client = mock(Client.class);
        when(client.settings()).thenReturn(Settings.EMPTY);
        when(client.threadPool()).thenReturn(threadPool);
        ClusterService clusterService = mock(ClusterService.class);
        DataFrameAnalyticsManager analyticsManager = mock(DataFrameAnalyticsManager.class);
        when(analyticsManager.isNodeShuttingDown()).thenReturn(nodeShuttingDown);
        DataFrameAnalyticsAuditor auditor = mock(DataFrameAnalyticsAuditor.class);
        PersistentTasksService persistentTasksService = new PersistentTasksService(clusterService, mock(ThreadPool.class), client);
        TaskManager taskManager = mock(TaskManager.class);

        List<PhaseProgress> progress = Arrays.asList(
            new PhaseProgress(ProgressTracker.REINDEXING, 100),
            new PhaseProgress(ProgressTracker.LOADING_DATA, 100),
            new PhaseProgress(ProgressTracker.WRITING_RESULTS, 30));

        StartDataFrameAnalyticsAction.TaskParams taskParams =
            new StartDataFrameAnalyticsAction.TaskParams(
                "job-id",
                Version.CURRENT,
                progress,
                false);

        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.getHits()).thenReturn(SearchHits.empty());
        doAnswer(withResponse(searchResponse)).when(client).execute(eq(SearchAction.INSTANCE), any(), any());

        IndexResponse indexResponse = mock(IndexResponse.class);
        doAnswer(withResponse(indexResponse)).when(client).execute(eq(IndexAction.INSTANCE), any(), any());

        DataFrameAnalyticsTask task =
            new DataFrameAnalyticsTask(
                123, "type", "action", null, Collections.emptyMap(), client, clusterService, analyticsManager, auditor, taskParams);
        task.init(persistentTasksService, taskManager, "task-id", 42);
        Exception exception = new Exception("some exception");

        task.setFailed(exception);

        verify(analyticsManager).isNodeShuttingDown();
        verify(client, atLeastOnce()).settings();
        verify(client, atLeastOnce()).threadPool();

        if (nodeShuttingDown == false) {
            // Verify progress was persisted
            ArgumentCaptor<IndexRequest> indexRequestCaptor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(client).execute(eq(SearchAction.INSTANCE), any(), any());
            verify(client).execute(eq(IndexAction.INSTANCE), indexRequestCaptor.capture(), any());

            IndexRequest indexRequest = indexRequestCaptor.getValue();
            assertThat(indexRequest.index(), equalTo(AnomalyDetectorsIndex.jobStateIndexWriteAlias()));
            assertThat(indexRequest.id(), equalTo("data_frame_analytics-job-id-progress"));

            try (XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY, DeprecationHandler.IGNORE_DEPRECATIONS, indexRequest.source().utf8ToString())) {
                StoredProgress parsedProgress = StoredProgress.PARSER.apply(parser, null);
                assertThat(parsedProgress.get(), equalTo(progress));
            }

            verify(client).execute(
                same(UpdatePersistentTaskStatusAction.INSTANCE),
                eq(new UpdatePersistentTaskStatusAction.Request(
                    "task-id", 42, new DataFrameAnalyticsTaskState(DataFrameAnalyticsState.FAILED, 42, "some exception"))),
                any());
        }
        verifyNoMoreInteractions(client, clusterService, analyticsManager, auditor, taskManager);
    }

    @SuppressWarnings("unchecked")
    private static <Response> Answer<Response> withResponse(Response response) {
        return invocationOnMock -> {
            ActionListener<Response> listener = (ActionListener<Response>) invocationOnMock.getArguments()[2];
            listener.onResponse(response);
            return null;
        };
    }
}