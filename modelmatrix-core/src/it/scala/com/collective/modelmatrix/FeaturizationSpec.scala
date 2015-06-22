package com.collective.modelmatrix

import java.nio.ByteBuffer

import com.collective.modelmatrix.FeaturizationError.FeatureColumnTypeDoesNotMatch
import com.collective.modelmatrix.catalog.{ModelInstanceIdentityFeature, ModelInstanceIndexFeature, ModelInstanceTopFeature}
import com.collective.modelmatrix.transform.{Identity, Index, Top, Transformer}
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.scalatest.{FlatSpec, GivenWhenThen}
import scodec.bits.ByteVector

import scalaz.{-\/, \/, \/-}

class FeaturizationSpec extends FlatSpec with GivenWhenThen with TestSparkContext {

  val sqlContext = ModelMatrix.sqlContext(sc)

  val schema = StructType(Seq(
    StructField("auction_id", LongType),
    StructField("adv_price", DoubleType),
    StructField("adv_type", IntegerType),
    StructField("adv_site", StringType),
    StructField("adv_strategy", LongType)
  ))

  val input = Seq(
    Row(1l, 1.23, 1, "cnn.com", 1L),
    Row(2l, 2.34, 2, "bbc.com", 2L),
    Row(3l, 0.12, 2, "hbo.com", 3L),
    Row(4l, 0.09, 3, "mashable.com", 4L)
  )

  val isActive = true
  val withAllOther = true

  // Model features
  val adPrice = ModelFeature(isActive, "Ad", "ad_price", "adv_price * 100.0", Identity)
  val adType = ModelFeature(isActive, "Ad", "ad_type", "adv_type", Top(95.0, withAllOther))
  val adSite = ModelFeature(isActive, "Ad", "ad_site", "adv_site", Index(0.5, withAllOther))
  val adStrategy = ModelFeature(isActive, "Ad", "ad_strategy", "adv_strategy", Top(100.0, withAllOther))

  // Feature instance

  val modelInstanceId = 12345

  val adPriceInstance = ModelInstanceIdentityFeature(1, modelInstanceId, adPrice, DoubleType, 1)

  val adTypeInstance = ModelInstanceTopFeature(2, modelInstanceId, adType, IntegerType, Seq(
    CategorialColumn.CategorialValue(2, "1", ByteVector(ByteBuffer.allocate(4).putInt(1).array()), 100, 100),
    CategorialColumn.CategorialValue(3, "2", ByteVector(ByteBuffer.allocate(4).putInt(2).array()), 100, 200),
    CategorialColumn.AllOther(4, 200, 400)
  ))

  val adSiteInstance = ModelInstanceIndexFeature(3, modelInstanceId, adSite, StringType, Seq(
    CategorialColumn.CategorialValue(5, "cnn.com", ByteVector("cnn.com".getBytes), 100, 100),
    CategorialColumn.CategorialValue(6, "bbc.com", ByteVector("bbc.com".getBytes), 100, 200)
  ))

  val adStrategyInstance = ModelInstanceTopFeature(4, modelInstanceId, adStrategy, LongType, Seq(
    CategorialColumn.CategorialValue(7, "1", ByteVector(ByteBuffer.allocate(8).putLong(1L).array()), 100, 100),
    CategorialColumn.CategorialValue(8, "2", ByteVector(ByteBuffer.allocate(8).putLong(2L).array()), 100, 200),
    CategorialColumn.CategorialValue(9, "3", ByteVector(ByteBuffer.allocate(8).putLong(3L).array()), 100, 200),
    CategorialColumn.CategorialValue(10, "4", ByteVector(ByteBuffer.allocate(8).putLong(4L).array()), 100, 200)
  ))

  val totalColumns = 10

  val auctionIdLabeling = Labeling("auction_id", identity[Long])

  val df = sqlContext.createDataFrame(sc.parallelize(input), schema)
  val transformed = Transformer.extractFeatures(df, Seq(adPrice, adType, adSite, adStrategy), auctionIdLabeling) match {
    case -\/(err) => sys.error(s"Can't extract features: $err")
    case \/-(suc) => suc
  }

  val featureExtraction = new Featurization(Seq(adPriceInstance, adTypeInstance, adSiteInstance, adStrategyInstance))

  "Feature Extraction" should "validate input schema against provided features" in {
    val validation = featureExtraction.validateLabeled(transformed)
    assert(validation.count(_.isRight) == 4)
  }

  it should "fail if extractType doesn't match" in {
    val brokenFeatureExtraction = new Featurization(Seq(adPriceInstance, adTypeInstance.copy(extractType = DoubleType), adSiteInstance))

    val validation = brokenFeatureExtraction.validateLabeled(transformed)
    assert(validation.count(_.isLeft) == 1)

    val error = validation.find(_.isLeft).head
    assert(error == \/.left(FeatureColumnTypeDoesNotMatch(adType.feature, IntegerType, DoubleType)))
  }

  it should "featurize input data frame" in {

    val featurized = featureExtraction.featurize(transformed, auctionIdLabeling).collect().toSeq.map(p => p._1 -> p._2).toMap
    assert(featurized.size == 4)

    // Columns:
    // 1 - adPrice
    // 2 - adType == 1
    // 3 - adType == 2
    // 4 - adType == all other
    // 5 - adSite == cnn.com
    // 6 - adSite == bbc.com

    assert(featurized(1l).asInstanceOf[SparseVector].size == 10)
    assert(featurized(1l).asInstanceOf[SparseVector].indices.toSeq.map(_ + 1) == Seq(1, 2, 5, 7))
    assert(featurized(1l).asInstanceOf[SparseVector].values.toSeq == Seq(1.23 * 100, 1.0, 1.0, 1.0))

    assert(featurized(2l).asInstanceOf[SparseVector].size == 10)
    assert(featurized(2l).asInstanceOf[SparseVector].indices.toSeq.map(_ + 1) == Seq(1, 3, 6, 8))
    assert(featurized(2l).asInstanceOf[SparseVector].values.toSeq == Seq(2.34 * 100, 1.0, 1.0, 1.0))

    assert(featurized(3l).asInstanceOf[SparseVector].size == 10)
    assert(featurized(3l).asInstanceOf[SparseVector].indices.toSeq.map(_ + 1) == Seq(1, 3, 9))
    assert(featurized(3l).asInstanceOf[SparseVector].values.toSeq == Seq(0.12 * 100, 1.0, 1.0))

    assert(featurized(4l).asInstanceOf[SparseVector].size == 10)
    assert(featurized(4l).asInstanceOf[SparseVector].indices.toSeq.map(_ + 1) == Seq(1, 4, 10))
    assert(featurized(4l).asInstanceOf[SparseVector].values.toSeq == Seq(0.09 * 100, 1.0, 1.0))

  }

  it should "correctly handle null columns" in {

    Given("row with null ad price and ad site")
    val withNullPriceAndSite = input :+ Row(5l, null /* ad price */, 2 /* ad type */, null /* ad site */, null /* ad strategy */)
    val df = sqlContext.createDataFrame(sc.parallelize(withNullPriceAndSite), schema)
    val transformed = Transformer.extractFeatures(df, Seq(adPrice, adType, adSite, adStrategy), auctionIdLabeling) match {
      case -\/(err) => sys.error(s"Can't extract features: $err")
      case \/-(suc) => suc
    }

    Then("it should be successfully featurized")
    val featurized = featureExtraction.featurize(transformed, auctionIdLabeling).collect().toSeq.map(p => p._1 -> p._2).toMap
    assert(featurized.size == 5)

    And("only ad type column should be defined")
    val features = featurized(5l)

    assert(features.asInstanceOf[SparseVector].size == 10)
    assert(features.asInstanceOf[SparseVector].indices.toSeq.map(_ + 1) == Seq(3))
    assert(features.asInstanceOf[SparseVector].values.toSeq == Seq(1.0))
  }
}
