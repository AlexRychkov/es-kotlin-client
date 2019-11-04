package org.elasticsearch.client

import io.inbot.eskotlinwrapper.IndexDAO
import io.inbot.eskotlinwrapper.ModelReaderAndWriter
import io.inbot.eskotlinwrapper.OldSuspendingActionListener.Companion.suspending
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.action.search.ClearScrollRequest
import org.elasticsearch.action.search.ClearScrollResponse
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.action.search.SearchScrollRequest
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.client.indices.CreateIndexResponse
import org.elasticsearch.client.sniff.SniffOnFailureListener
import org.elasticsearch.client.sniff.Sniffer
import org.elasticsearch.common.unit.TimeValue

/**
 * Factory method that gives you sane defaults that will allow you to quickly connect to your cluster whether it is in
 * Elastic Cloud that requires authentication or a local cluster.
 *
 * If you need basic authentication, simply set [user] and [password] to the appropriate values.
 *
 * If you need https, set [https] to true.
 *
 * If you are connecting to a local cluster and don't use a loadbalancer, it is advisable to configure the sniffer.
 *
 * This enables client side load balancing between the nodes and adds some intelligence to deal with nodes being
 * unresponsive or cluster layout changing. Simply set [useSniffer] to true. Note, beware that docker internal ips
 * may not be reachable from your client and make sure that the addresses returned by `/_nodes/http` are
 * actually reachable from where your client is running.
 */
fun create(
    host: String = "localhost",
    port: Int = 9200,
    https: Boolean = false,
    user: String? = null,
    password: String? = null,
    useSniffer: Boolean = false,
    sniffAfterFailureDelayMillis: Int = 30000,
    sniffIntervalMillis: Int = 10000
): RestHighLevelClient {
    val sniffOnFailureListener = SniffOnFailureListener()
    var restClientBuilder = RestClient.builder(HttpHost(host, port, if (https) "https" else "http"))
    if (!user.isNullOrBlank()) {
        restClientBuilder = restClientBuilder.setHttpClientConfigCallback {
            val basicCredentialsProvider = BasicCredentialsProvider()
            basicCredentialsProvider.setCredentials(
                AuthScope.ANY,
                UsernamePasswordCredentials(user, password)
            )
            it.setDefaultCredentialsProvider(basicCredentialsProvider)
        }
        if (useSniffer) {
            restClientBuilder.setFailureListener(sniffOnFailureListener)
        }
    }
    val restHighLevelClient = RestHighLevelClient(restClientBuilder)
    if (useSniffer) {
        val sniffer = Sniffer.builder(restHighLevelClient.lowLevelClient).setSniffAfterFailureDelayMillis(sniffAfterFailureDelayMillis).setSniffIntervalMillis(sniffIntervalMillis).build()
        sniffOnFailureListener.setSniffer(sniffer)
    }
    return restHighLevelClient
}

@Suppress("FunctionName")
@Deprecated(message = "Use the create function", replaceWith = ReplaceWith("create(host,port,https,user,password,useSniffer,sniffAfterFailureDelayMillis,sniffIntervalMillis)"))
fun RestHighLevelClient(
    host: String = "localhost",
    port: Int = 9200,
    https: Boolean = false,
    user: String? = null,
    password: String? = null,
    useSniffer: Boolean = false,
    sniffAfterFailureDelayMillis: Int = 30000,
    sniffIntervalMillis: Int = 10000
): RestHighLevelClient = create(host, port, https, user, password, useSniffer, sniffAfterFailureDelayMillis, sniffIntervalMillis)

/**
 * Create a new Data Access Object (DAO), aka. repository class. If you've used J2EE style frameworks, you should be familiar with this pattern.
 *
 * This abstracts the business of telling the client which index to run against and serializing/deserializing documents in it.
 *
 */
fun <T : Any> RestHighLevelClient.crudDao(
    index: String,
    modelReaderAndWriter: ModelReaderAndWriter<T>,
    type: String = "_doc",
    readAlias: String = index,
    writeAlias: String = index,
    refreshAllowed: Boolean = false,
    defaultRequestOptions: RequestOptions = RequestOptions.DEFAULT
): IndexDAO<T> {
    return IndexDAO(
        indexName = index,
        client = this,
        modelReaderAndWriter = modelReaderAndWriter,
        refreshAllowed = refreshAllowed,
        type = type,
        indexReadAlias = readAlias,
        indexWriteAlias = writeAlias,
        defaultRequestOptions = defaultRequestOptions

    )
}

/**
 * Search documents in the index. Expects a search block that takes a `SearchRequest` where you specify the query.
 * The search request already has your index. Also see extension functions added in `org.elasticsearch.action.search.SearchRequest`
 */
fun RestHighLevelClient.search(
    requestOptions: RequestOptions = RequestOptions.DEFAULT,
    block: SearchRequest.() -> Unit
): SearchResponse {
    val searchRequest = SearchRequest()
    block.invoke(searchRequest)
    return this.search(searchRequest, requestOptions)
}

/**
 * Suspend version of search that you can use in a co-routine context. Works the same otherwise.
 */
suspend fun RestHighLevelClient.searchAsync(
    requestOptions: RequestOptions = RequestOptions.DEFAULT,
    block: SearchRequest.() -> Unit
): SearchResponse {
    val searchRequest = SearchRequest()
    block.invoke(searchRequest)
    return suspending {
        this.searchAsync(searchRequest, requestOptions, it)
    }
}

/**
 * Get the next page of a scrolling search. Note, use the DAO to do scrolling searches and avoid manually doing these requests.
 */
fun RestHighLevelClient.scroll(
    scrollId: String,
    ttl: Long,
    requestOptions: RequestOptions = RequestOptions.DEFAULT
): SearchResponse {
    return this.scroll(
        SearchScrollRequest(scrollId).scroll(
            TimeValue.timeValueMinutes(
                ttl
            )
        ), requestOptions
    )
}

/**
 * Get the next page of a scrolling search. Note, use the DAO to do scrolling searches and avoid manually doing these requests.
 *
 * Note, there currently is no async version of this in the DAO.
 */
//suspend fun RestHighLevelClient.scrollAsync(
//    scrollId: String,
//    ttl: Long,
//    requestOptions: RequestOptions = RequestOptions.DEFAULT
//): SearchResponse {
//    return suspending {
//        this.scrollAsync(
//            SearchScrollRequest(scrollId).scroll(
//                TimeValue.timeValueMinutes(
//                    ttl
//                )
//            ), requestOptions, it
//        )
//    }
//}

/**
 * Clear the scroll after you are done. If you use the DAO for scrolling searches, this is called for you.
 */
fun RestHighLevelClient.clearScroll(
    vararg scrollIds: String,
    requestOptions: RequestOptions = RequestOptions.DEFAULT
): ClearScrollResponse {
    val clearScrollRequest = ClearScrollRequest()
    scrollIds.forEach { clearScrollRequest.addScrollId(it) }
    return this.clearScroll(clearScrollRequest, requestOptions)
}

/**
 * Clear the scroll after you are done. If you use the DAO for scrolling searches, this is called for you.
 */
//suspend fun RestHighLevelClient.clearScrollAsync(
//    vararg scrollIds: String,
//    requestOptions: RequestOptions = RequestOptions.DEFAULT
//): ClearScrollResponse {
//    // FIXME figure out a way to use this to create some kind of suspending Sequence<SearchResponse>, this seems to be hard currently
//    return suspending {
//        val clearScrollRequest = ClearScrollRequest()
//        scrollIds.forEach { clearScrollRequest.addScrollId(it) }
//        this.clearScrollAsync(clearScrollRequest, requestOptions, it)
//    }
//}

/**
 * Create index asynchronously.
 */
//suspend fun IndicesClient.createIndexAsync(
//    index: String,
//    requestOptions: RequestOptions = RequestOptions.DEFAULT,
//    block: CreateIndexRequest.() -> Unit
//): CreateIndexResponse {
//    val request = CreateIndexRequest(index)
//    block.invoke(request)
//    return suspending {
//        this.createAsync(request, requestOptions, it)
//    }
//}

//suspend fun RestHighLevelClient.bulkAsync(bulkRequest: BulkRequest, options: RequestOptions = RequestOptions.DEFAULT): BulkResponse {
//    return suspending {
//        this.bulkAsync(bulkRequest, options, it)
//    }
//}
