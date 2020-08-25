/*
 * Copyright 2019-2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spanner.r2dbc.v2;

import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.r2dbc.SpannerConnectionConfiguration;
import com.google.cloud.spanner.r2dbc.client.Client;
import com.google.cloud.spanner.r2dbc.statement.StatementParser;
import com.google.cloud.spanner.r2dbc.statement.StatementType;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.ValidationDepth;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class SpannerClientLibraryConnection implements Connection {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SpannerClientLibraryConnection.class);

  private DatabaseClient dbClient;

  private DatabaseAdminClient dbAdminClient;

  // fall back to grpc for unsupported client library async functionality (DDL)
  private Client grpcClient;

  private SpannerConnectionConfiguration config;

  private final DatabaseClientReactiveAdapter clientLibraryAdapter;

  // TODO: make thread pool customizable
  private ExecutorService executorService = Executors.newFixedThreadPool(4);

  /**
   * Cloud Spanner implementation of R2DBC Connection SPI.
   * @param dbClient Cloud Spanner client library database client
   * @param dbAdminClient  Cloud Spanner client library DDL operations client
   * @param grpcClient TEMPORARY - this will go away
   * @param config driver configuration extracted from URL or passed directly to connection factory.
   */
  public SpannerClientLibraryConnection(DatabaseClient dbClient,
      DatabaseAdminClient dbAdminClient,
      Client grpcClient,
      SpannerConnectionConfiguration config) {
    this.dbClient = dbClient;
    this.dbAdminClient = dbAdminClient;
    this.grpcClient = grpcClient;
    this.config = config;
    this.clientLibraryAdapter = new DatabaseClientReactiveAdapter(dbClient, this.executorService);

  }

  @Override
  public Publisher<Void> beginTransaction() {

    return this.clientLibraryAdapter.beginTransaction();
  }

  @Override
  public Publisher<Void> commitTransaction() {
    return this.clientLibraryAdapter.commitTransaction();
  }

  @Override
  public Batch createBatch() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Publisher<Void> createSavepoint(String name) {
    throw new UnsupportedOperationException();
  }

  // TODO: test whether select statements interspersed with update statements need to be handled
  // as part of async flow
  @Override
  public Statement createStatement(String query) {
    StatementType type = StatementParser.getStatementType(query);
    if (type == StatementType.DDL) {
      LOGGER.debug("DDL statement detected: " + query);
      return new SpannerClientLibraryDdlStatement(query, this.grpcClient, this.config);
    } else if (type == StatementType.DML) {
      LOGGER.debug("DML statement detected: " + query);
      return new SpannerClientLibraryDmlStatement(this.clientLibraryAdapter, query);
    }
    return new SpannerClientLibraryStatement(this.dbClient, query);
  }

  @Override
  public boolean isAutoCommit() {
    return false;
  }

  @Override
  public ConnectionMetadata getMetadata() {
    throw new UnsupportedOperationException();
  }

  @Override
  public IsolationLevel getTransactionIsolationLevel() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Publisher<Void> releaseSavepoint(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Publisher<Void> rollbackTransaction() {
    return this.clientLibraryAdapter.rollback();
  }

  @Override
  public Publisher<Void> rollbackTransactionToSavepoint(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Publisher<Void> setAutoCommit(boolean autoCommit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Publisher<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Publisher<Boolean> validate(ValidationDepth depth) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Publisher<Void> close() {
    return this.clientLibraryAdapter.close()
        .then(Mono.fromRunnable(() -> {
          LOGGER.debug("  shutting down executor service");
          this.executorService.shutdown();
        }));
  }

}