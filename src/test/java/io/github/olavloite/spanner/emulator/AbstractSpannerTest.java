package io.github.olavloite.spanner.emulator;

import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.spanner.BatchClient;
import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Instance;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Operation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import com.google.cloud.spanner.TransactionRunner.TransactionCallable;
import com.google.spanner.admin.database.v1.CreateDatabaseMetadata;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import com.google.spanner.admin.instance.v1.CreateInstanceMetadata;
import io.github.olavloite.spanner.emulator.util.CloudSpannerOAuthUtil;
import io.github.olavloite.spanner.emulator.util.EnglishNumberToWords;

public abstract class AbstractSpannerTest {
  static {
    System.setProperty("java.util.logging.config.file", "log-config.properties");
  }
  private static final Log log = LogFactory.getLog(AbstractSpannerTest.class);

  private static final String DEFAULT_HOST = "https://emulator.googlecloudspanner.com:8443";

  protected static final String PROJECT_ID = "test-project";
  protected static String INSTANCE_ID;
  protected static final String DATABASE_ID = "test-database";

  private static InstanceAdminClient instanceAdminClient;
  private static DatabaseAdminClient databaseAdminClient;
  private static DatabaseClient databaseClient;
  private static BatchClient batchClient;

  public static String getHost() {
    return System.getProperty("host", DEFAULT_HOST);
  }

  @BeforeClass
  public static void setup() {
    INSTANCE_ID = "test-instance-" + new Random().nextInt(100000000);
    log.info("Setting up test with instance id " + INSTANCE_ID);
    try {
      String credentialsPath = "emulator.json";
      GoogleCredentials credentials = CloudSpannerOAuthUtil.getCredentialsFromFile(credentialsPath);
      SpannerOptions options = SpannerOptions.newBuilder().setProjectId(PROJECT_ID)
          .setHost(getHost()).setCredentials(credentials).build();
      Spanner spanner = options.getService();
      instanceAdminClient = spanner.getInstanceAdminClient();
      databaseAdminClient = spanner.getDatabaseAdminClient();

      // First delete anything that might be there
      clearCurrentInstanceAndDatabases();
      // Then create a new test instance
      Operation<Instance, CreateInstanceMetadata> createInstance =
          instanceAdminClient.createInstance(InstanceInfo
              .newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID)).setDisplayName("Test Instance")
              .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "europe-west1")).setNodeCount(1)
              .build());
      assertTrue(createInstance.isDone());
      Operation<Database, CreateDatabaseMetadata> createDatabase =
          databaseAdminClient.createDatabase(INSTANCE_ID, DATABASE_ID, Collections.emptyList());
      Database database = createDatabase.waitFor().getResult();
      databaseClient = spanner.getDatabaseClient(database.getId());
      batchClient = spanner.getBatchClient(database.getId());
      log.info("Finished setting up test");
    } catch (Throwable t) {
      log.error(t.getMessage(), t);
    }
  }

  @AfterClass
  public static void teardown() {
    clearCurrentInstanceAndDatabases();
    log.info("Dropped instance " + INSTANCE_ID);
    INSTANCE_ID = null;
  }

  private static void clearCurrentInstanceAndDatabases() {
    try {
      Instance instance = instanceAdminClient.getInstance(INSTANCE_ID);
      Page<Database> databases = databaseAdminClient.listDatabases(instance.getId().getInstance());
      databases.iterateAll().forEach(d -> d.drop());
      instanceAdminClient.deleteInstance(instance.getId().getInstance());
    } catch (SpannerException e) {
      if (e.getErrorCode() != ErrorCode.NOT_FOUND) {
        throw e;
      }
    }
  }

  protected static InstanceAdminClient getInstanceAdminClient() {
    return instanceAdminClient;
  }

  protected static DatabaseAdminClient getDatabaseAdminClient() {
    return databaseAdminClient;
  }

  protected static DatabaseClient getDatabaseClient() {
    return databaseClient;
  }

  protected static BatchClient getBatchClient() {
    return batchClient;
  }

  protected static void createNumberTable() {
    Operation<Void, UpdateDatabaseDdlMetadata> operation =
        getDatabaseAdminClient().updateDatabaseDdl(INSTANCE_ID, DATABASE_ID, Arrays.asList(
            "create table number (number int64 not null, name string(100) not null) primary key (number)"),
            null).waitFor();
    assertTrue(operation.isDone());
    assertTrue(operation.isSuccessful());
  }

  protected static void createIndexOnNumberName() {
    Operation<Void, UpdateDatabaseDdlMetadata> operation =
        getDatabaseAdminClient().updateDatabaseDdl(INSTANCE_ID, DATABASE_ID,
            Arrays.asList("create index idx_number_name on number (name)"), null).waitFor();
    assertTrue(operation.isDone());
    assertTrue(operation.isSuccessful());
  }

  protected static void insertTestNumbers(long rows) {
    insertTestNumbers(1, rows);
  }

  protected static void insertTestNumbers(long start, long noOfRows) {
    TransactionRunner runner = getDatabaseClient().readWriteTransaction();
    runner.run(new TransactionCallable<Void>() {
      @Override
      public Void run(TransactionContext transaction) throws Exception {
        for (long counter = start; counter < (start + noOfRows); counter++) {
          Mutation mutation = Mutation.newInsertBuilder("number").set("number").to(counter)
              .set("name").to(EnglishNumberToWords.convert(counter)).build();
          transaction.buffer(mutation);
        }
        return null;
      }
    });
  }

  protected static void dropNumberTable() {
    Operation<Void, UpdateDatabaseDdlMetadata> operation = getDatabaseAdminClient()
        .updateDatabaseDdl(INSTANCE_ID, DATABASE_ID, Arrays.asList("drop table number"), null)
        .waitFor();
    assertTrue(operation.isDone());
    assertTrue(operation.isSuccessful());
  }

  protected static void executeDdl(String ddl) {
    executeDdl(Arrays.asList(ddl));
  }

  protected static void executeDdl(Iterable<String> ddl) {
    Operation<Void, UpdateDatabaseDdlMetadata> operation =
        getDatabaseAdminClient().updateDatabaseDdl(INSTANCE_ID, DATABASE_ID, ddl, null).waitFor();
    operation.getResult();
    assertTrue(operation.isDone());
    assertTrue(operation.isSuccessful());
  }

}
