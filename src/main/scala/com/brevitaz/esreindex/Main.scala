package com.brevitaz.esreindex

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.source.Indexable
import com.sksamuel.elastic4s.{ElasticClient, ElasticsearchClientUri, SearchType}
import com.typesafe.config.Config
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.search.SearchHit
import org.json4s.jackson.Serialization._

/**
  * Created by pranavshukla on 25/06/16.
  */
object Main {

  def main(args: Array[String]) {


    import com.typesafe.config.ConfigFactory

    val conf = ConfigFactory.load

    val srcIndex = conf.getString("esreindex.source.indexName")
    val srcType = conf.getString("esreindex.source.typeName")
    val srcCluster: String = conf.getString("esreindex.source.cluster")
    val srcUri: String = conf.getString("esreindex.source.uri")

    val srcLatLonTargetLatLon: Option[(String, String, String, Boolean)] = if(conf.getString("esreindex.geopoint.latitudeField")!=null) {
      Some((conf.getString("esreindex.geopoint.latitudeField"), conf.getString("esreindex.geopoint.longitudeField"), conf.getString("esreindex.geopoint.targetField"), conf.getBoolean("esreindex.geopoint.keepOriginalFields")))
    } else None

    val targetIndex = conf.getString("esreindex.target.indexName")
    val targetType = conf.getString("esreindex.target.typeName")
    val targetCluster: String = conf.getString("esreindex.target.cluster")
    val targetUri: String = conf.getString("esreindex.target.uri")

    val termField = conf.getString("esreindex.query.termQuery.field")
    val termValue = conf.getString("esreindex.query.termQuery.value")
    val perShardBatchSize = conf.getInt("esreindex.query.perShardBatchSize")
    val perShardTotalLimit = conf.getInt("esreindex.query.perShardTotalLimit")

    val sourceClient: ElasticClient = getEsClient(srcIndex, srcType, srcCluster, srcUri)

    val targetClient: ElasticClient = getEsClient(targetIndex, targetType, targetCluster, targetUri)

    var result = sourceClient.execute {
      search in srcIndex / srcType query {
        termQuery(termField, termValue)
      } scroll "1m" size perShardBatchSize limit perShardTotalLimit //searchType SearchType.Scan
    }.await


    println (s"result size is ${result.getHits.getHits.length}")

    import org.json4s._
    import org.json4s.jackson.JsonMethods._

 //   import scala.collection.JavaConverters._

    while(result.getHits.getHits.nonEmpty) {

      val hits: List[SearchHit] = result.getHits.getHits.toList


      println(s"scrolling fetched - ${hits.length} records in the batch")

      val transformedObjects: List[JObject] = hits.map {
        hit =>

          val doc = new String(hit.source(), "UTF-8")

          val root: JObject = parse(doc).asInstanceOf[JObject]

          var keyValuePairsExceptLatLon: List[JField] = root.obj.filter { case (key, value) =>

            srcLatLonTargetLatLon match {
              case Some((srcLatField, srcLonField, tarLoc, keepOriginalFields)) =>
                if(key.equals(srcLatField) || key.equals(srcLonField)) {
                  keepOriginalFields
                } else
                  true
              case None =>
                true
            }

          }

          srcLatLonTargetLatLon match {
            case Some((srcLatField, srcLonField, tarLoc, _)) =>
              val latField: Option[(String, JValue)] = root.obj.find{case(key, value) => key.equals(srcLatField)}
              val lonField: Option[(String, JValue)] = root.obj.find{case(key, value) => key.equals(srcLonField)}

              val tuples: Iterable[((String, JValue), (String, JValue))] = latField zip lonField
              val latLonField: Iterable[String] = tuples map {case (lat, lon) => lat._2.asInstanceOf[JString].values + "," + lon._2.asInstanceOf[JString].values}
              if(latLonField.nonEmpty)
                keyValuePairsExceptLatLon = keyValuePairsExceptLatLon.+:(JField(tarLoc, JString(latLonField.head)))
            case None =>
          }

          JObject(keyValuePairsExceptLatLon)
      }

      case object RawDocIndexable extends Indexable[String] {
        def json(t: String): String = t
      }

      implicit val indexable = RawDocIndexable

      val jsonList: List[String] = transformedObjects.map(jobj => compact(render(jobj)))
      val indexRequests = jsonList map { evt:String => index into targetIndex / targetType source evt }

      val response = targetClient.execute {
        bulk(
          indexRequests
        )
      }.await

      result = sourceClient.execute {
        search scroll result.getScrollId keepAlive "1m"
      }.await
    }

  }

  def getEsClient(indexName: String, typeName: String, clusterName: String, uri: String): ElasticClient = {
    val settings = ImmutableSettings.settingsBuilder().put("cluster.name", clusterName).build()
    val indexAndType = indexName + "/" + typeName
    ElasticClient.remote(settings, ElasticsearchClientUri(uri))
  }
}
