import com.amazonaws.services.glue.GlueContext
import com.amazonaws.services.glue.log.GlueLogger
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

object GlueApp {

  def main(sysArgs: Array[String]) {

    val args = GlueArgParser.getResolvedOptions(sysArgs, Seq("JOB_NAME", "KEYSPACE_NAME", "TABLE_NAME", "DRIVER_CONF").toArray)

    val driverConfFileName = args("DRIVER_CONF")

    val conf = new SparkConf()
        .setAll(
         Seq(
            ("spark.task.maxFailures",  "10"),
            ("spark.cassandra.connection.config.profile.path",  driverConfFileName),
            ("spark.cassandra.query.retry.count", "1000"),

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

    import com.datastax.spark.connector._
    import org.apache.spark.sql.cassandra._
    import sparkSession.implicits._

    Job.init(args("JOB_NAME"), glueContext, args.asJava)

    val tableName = args("TABLE_NAME")
    val keyspaceName = args("KEYSPACE_NAME")


    val tableDf = sparkSession.read
      .format("org.apache.spark.sql.cassandra")
      .options(Map( "table" -> tableName, "keyspace" -> keyspaceName))
      .load()

    val total =  tableDf.toJavaRDD.count()

    val logger = new GlueLogger

    logger.info("Total number of rows in table:" + total)

    Job.commit()
  }
}