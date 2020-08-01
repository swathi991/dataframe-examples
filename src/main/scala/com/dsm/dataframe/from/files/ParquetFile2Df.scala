package com.dsm.dataframe.from.files

import com.dsm.utils.Constants
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window

object ParquetFile2Df {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder
      .master("local[*]")
      .appName("Parquet File to Dataframe")
      .getOrCreate()
    spark.sparkContext.setLogLevel(Constants.ERROR)

    spark.sparkContext.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", Constants.ACCESS_KEY)
    spark.sparkContext.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", Constants.SECRET_ACCESS_KEY)

    println("\nCreating dataframe from parquet file using 'SparkSession.read.parquet()',")
    val nycOmoDf = spark.read
      .parquet("s3n://" + Constants.S3_BUCKET + "/NYC_OMO")
      .repartition(5)

    println("# of records = " + nycOmoDf.count())
    println("# of partitions = " + nycOmoDf.rdd.getNumPartitions)

    nycOmoDf.printSchema()

    println("Summery of NYC Open Market Order (OMO) charges dataset,")
    nycOmoDf.describe().show()

    println("OMO frequency distribution of different Boroughs,")
    nycOmoDf.groupBy("Boro")
      .agg("Boro" -> "count")
      .withColumnRenamed("count(Boro)", "OrderFrequency")
      .show()

    println("OMO's Zip & Borough list,")
    import spark.implicits._
    val boroZipDf = nycOmoDf
      .select($"Boro", $"Zip".cast(IntegerType))
      .groupBy("Boro")
      .agg("Zip" -> "collect_set")
      .withColumnRenamed("collect_set(Zip)", "ZipList")
      .withColumn("ZipCount", size($"ZipList"))

    boroZipDf
        .select("Boro", "ZipCount", "ZipList")
        .show(false)

    val omoCreateDatePartitionWindow = Window.partitionBy("OMOCreateDate")
    val omoDailyFreq = nycOmoDf
        .withColumn("OMODailyFreq",
          count("OMOID").over(omoCreateDatePartitionWindow).alias("OMODailyFreq"))

    println("# of partitions in window'ed OM dataframe = " + omoDailyFreq.count())
    omoDailyFreq.show(50, false)


    omoDailyFreq.select("OMOCreateDate", "OMODailyFreq")
        .distinct()
        .show(false)

    omoDailyFreq
      .repartition(10)
      .write
      .mode(SaveMode.Overwrite)
      .parquet("s3n://" + Constants.S3_BUCKET + "/nyc_omo_data")

    spark.close()
  }
}
