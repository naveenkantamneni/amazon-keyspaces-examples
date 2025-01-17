## Using Glue Import Example
This example provides scala script for importing data to an Amazon Keyspaces table data from S3 using AWS Glue.

## Prerequisites
* Amazon Keyspaces table to import
* Amazon S3 bucket to retrieve backups
* Amazon S3 bucket to store job configuration and script

### Import to S3
The following example imports data to Amazon Keyspaces using the spark-cassandra-connector. The script takes three parameters KEYSPACE_NAME, KEYSPACE_TABLE, S3_URI


```
import com.amazonaws.services.glue.GlueContext
import com.amazonaws.services.glue.util.GlueArgParser
import com.amazonaws.services.glue.util.Job
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.from_json
import org.apache.spark.sql.streaming.Trigger
import scala.collection.JavaConverters._
import com.datastax.spark.connector._
import org.apache.spark.sql.cassandra._
import org.apache.spark.sql.SaveMode._
import com.datastax.spark.connector._
import org.apache.spark.sql.cassandra._
import com.datastax.spark.connector.cql._
import com.datastax.oss.driver.api.core._
    
import org.apache.spark.sql.functions.rand

import com.amazonaws.services.glue.log.GlueLogger



object GlueApp {

  def main(sysArgs: Array[String]) {

  val args = GlueArgParser.getResolvedOptions(sysArgs, Seq("JOB_NAME", "KEYSPACE_NAME", "TABLE_NAME", "DRIVER_CONF", "FORMAT", "S3_URI").toArray)

  val driverConfFileName = args("DRIVER_CONF")

  val conf = new SparkConf()
      .setAll(
       Seq(
           ("spark.task.maxFailures",  "10"),
             
          
          ("spark.cassandra.connection.config.profile.path",  driverConfFileName),
          ("spark.cassandra.query.retry.count", "1000"),
          ("spark.cassandra.output.consistency.level",  "LOCAL_QUORUM"),//WRITES
          ("spark.cassandra.input.consistency.level",  "LOCAL_ONE"),//READS

          ("spark.cassandra.sql.inClauseToJoinConversionThreshold", "0"),
          ("spark.cassandra.sql.inClauseToFullScanConversionThreshold", "0"),
          ("spark.cassandra.concurrent.reads", "512"),

          ("spark.cassandra.output.concurrent.writes", "15"),
          ("spark.cassandra.output.batch.grouping.key", "none"),
          ("spark.cassandra.output.batch.size.rows", "1")
      ))

    val spark: SparkContext = new SparkContext(conf)
    val glueContext: GlueContext = new GlueContext(spark)
    val sparkSession: SparkSession = glueContext.getSparkSession

    import sparkSession.implicits._

    Job.init(args("JOB_NAME"), glueContext, args.asJava)
    
    val logger = new GlueLogger
    
    //validation steps for peers and partitioner 
    val connector = CassandraConnector.apply(conf);
    val session = connector.openSession();
    val peersCount = session.execute("SELECT * FROM system.peers").all().size()
    
    val partitioner = session.execute("SELECT partitioner from system.local").one().getString("partitioner")
    
    logger.info("Total number of seeds:" + peersCount);
    logger.info("Configured partitioner:" + partitioner);
    
    if(peersCount == 0){
       throw new Exception("No system peers found. Check required permissions to read from the system.peers table. If using VPCE check permissions for describing VPCE endpoints. https://docs.aws.amazon.com/keyspaces/latest/devguide/vpc-endpoints.html")
    }
    
    if(partitioner.equals("com.amazonaws.cassandra.DefaultPartitioner")){
        throw new Exception("Sark requires the use of RandomPartitioner or Murmur3Partitioner. See Working with partioners in Amazon Keyspaces documentation. https://docs.aws.amazon.com/keyspaces/latest/devguide/working-with-partitioners.html")
    }
    
    val tableName = args("TABLE_NAME")
    val keyspaceName = args("KEYSPACE_NAME")
    val backupFormat = args("FORMAT")
    val s3bucketBackupsLocation = args("S3_URI")	


    val orderedData = sparkSession.read.format(backupFormat).load(s3bucketBackupsLocation)	

   //You want randomize data before loading to maximize table throughput and avoid WriteThottleEvents	
   //Data exported from another database or Cassandra may be ordered by primary key.	
   //With Amazon Keyspaces you want to load data in a random way to use all available resources.	
   //The following command will randomize the data.	
   val shuffledData = orderedData.orderBy(rand())	

   shuffledData.write.format("org.apache.spark.sql.cassandra").mode("append").option("keyspace", keyspaceName).option("table", tableName).save()

   Job.commit()
  }
}

```
## Update the partitioner for your account
In Apache Cassandra, partitioners control which nodes data is stored on in the cluster. Partitioners create a numeric token using a hashed value of the partition key. Cassandra uses this token to distribute data across nodes.  To use Apache Spark or AWS glue you may need to update the partitioner if set to DefaultPartitioner or RandomPartitioner to Mumur3Partitioner. You can execute this CQL command from the Amazon Keyspaces console [CQL editor](https://console.aws.amazon.com/keyspaces/home#cql-editor)

```
SELECT partitioner FROM system.local;

UPDATE system.local set partitioner='org.apache.cassandra.dht.Murmur3Partitioner' where key='local';
```
For more info see [Working with partitioners](https://docs.aws.amazon.com/keyspaces/latest/devguide/working-with-partitioners.html)


## Create IAM ROLE for AWS Glue
Create a new AWS service role named 'GlueKeyspacesImport' with AWS Glue as a trusted entity.

Included is a sample permissions-policy for executing Glue job. You can use managed policies AWSGlueServiceRole, AmazonKeyspacesFullAccess, read access to S3 bucket containing spack-cassandra-connector jar, configuration. Read access to S3 bucket containing backups.


## Cassandra driver configuration to connect to Amazon Keyspaces
The following configuration for connecting to Amazon Keyspaces with the spark-cassandra connector.

Using the RateLimitingRequestThrottler we can ensure that request do not exceed configured Keyspaces capacity. The G1.X DPU creates one executor per worker. The RateLimitingRequestThrottler in this example is set for 1000 request per second. With this configuration and G.1X DPU you will achieve 1000 request per Glue worker. Adjust the max-requests-per-second accordingly to fit your workload. Increase the number of workers to scale throughput to a table.

```

datastax-java-driver {
  basic.request.default-idempotence = true
  basic.contact-points = [ "cassandra.us-east-1.amazonaws.com:9142"]
  advanced.reconnect-on-init = true

   basic.load-balancing-policy {
        local-datacenter = "us-east-1"
     }


    advanced.throttler = {
      class = RateLimitingRequestThrottler
      max-requests-per-second = 1000
      max-queue-size = 50000
      drain-interval = 1 millisecond
    }

   advanced.ssl-engine-factory {
      class = DefaultSslEngineFactory
      hostname-validation = false
    }

    advanced.connection.pool.local.size = 2
    advanced.resolve-contact-points = false

}

```

## Create S3 bucket to store job artifacts
The AWS Glue ETL job will need to access jar dependencies, driver configuration, and scala script.
```
aws s3 mb s3://amazon-keyspaces-artifacts
```

## Create S3 bucket to store job artifacts
The AWS Glue ETL job will use an s3 bucket to backup keyspaces table data.
```
aws s3 mb s3://amazon-keyspaces-backups
```


## Create S3 bucket for Shuffle space
With NoSQL its common to shuffle large sets of data. This can overflow local disk.  With AWS Glue, you can  use Amazon S3 to store Spark shuffle and spill data. This solution disaggregates compute and storage for your Spark jobs, and gives complete elasticity and low-cost shuffle storage, allowing you to run your most shuffle-intensive workloads reliably.

```
aws s3 mb s3://amazon-keyspaces-glue-shuffle
```



## Upload job artifacts to S3
The job will require
* The spark-cassandra-connector to allow reads from Amazon Keyspaces. Amazon Keyspaces recommends version 2.5.2 of the spark-cassandra-connector or above.
* application.conf containing the cassandra driver configuration for Keyspaces access
* import-sample.scala script containing the import code.

```
curl -L -O https://repo1.maven.org/maven2/com/datastax/spark/spark-cassandra-connector-assembly_2.11/2.5.2/spark-cassandra-connector-assembly_2.11-2.5.2.jar

aws s3api put-object --bucket amazon-keyspaces-artifacts --key jars/spark-cassandra-connector-assembly_2.11-2.5.2.jar --body spark-cassandra-connector-assembly_2.11-2.5.2.jar

aws s3api put-object --bucket amazon-keyspaces-artifacts --key conf/cassandra-application.conf --body cassandra-application.conf

aws s3api put-object --bucket amazon-keyspaces-artifacts --key scripts/import-sample.scala --body import-sample.scala

```
### Create AWS Glue ETL Job
You can use the following command to create a glue job using the script provided in this example. You can also take the parameters and enter them into the AWS Console.
```
aws glue create-job \
    --name "AmazonKeyspacesImport" \
    --role "GlueKeyspacesImport" \
    --description "Import Amazon Keyspaces table to s3" \
    --glue-version "2.0" \
    --number-of-workers 5 \
    --worker-type "G.1X" \
    --command "Name=glueetl,ScriptLocation=s3://amazon-keyspaces-artifacts/scripts/import-sample.scala" \
    --default-arguments '{
        "--job-language":"scala",
        "--FORMAT":"parquet",
        "--KEYSPACE_NAME":"my_keyspace",
        "--TABLE_NAME":"my_table",
        "--S3_URI":"s3://amazon-keyspaces-backups/snap-shots/",
        "--DRIVER_CONF":"cassandra-application.conf",
        "--USERNAME":"example-at-000000000",
        "--PASSWORD":"EXAMPLEKEYSAMPLE=",
        "--extra-jars":"s3://amazon-keyspaces-artifacts/jars/spark-cassandra-connector-assembly_2.11-2.5.2.jar",
        "--extra-files":"s3://amazon-keyspaces-artifacts/conf/cassandra-application.conf",
        "--enable-continuous-cloudwatch-log":"true",
        "--write-shuffle-files-to-s3":"true",
        "--write-shuffle-spills-to-s3":"true",
        "--TempDir":"s3://amazon-keyspaces-glue-shuffle",
        "--class":"GlueApp"
    }'
```
