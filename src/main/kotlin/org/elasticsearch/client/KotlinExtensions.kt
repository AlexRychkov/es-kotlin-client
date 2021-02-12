package org.elasticsearch.client

import com.jillesvangurp.eskotlinwrapper.AsyncIndexRepository
import com.jillesvangurp.eskotlinwrapper.IndexRepository
import com.jillesvangurp.eskotlinwrapper.IndexSettingsAndMappingsDSL
import com.jillesvangurp.eskotlinwrapper.JacksonModelReaderAndWriter
import com.jillesvangurp.eskotlinwrapper.ModelReaderAndWriter
import com.jillesvangurp.eskotlinwrapper.SuspendingActionListener.Companion.suspending
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.action.search.ClearScrollRequest
import org.elasticsearch.action.search.ClearScrollResponse
import org.elasticsearch.action.search.LOGGING_DEPRECATION_HANDLER
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.action.search.SearchScrollRequest
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.client.sniff.SniffOnFailureListener
import org.elasticsearch.client.sniff.Sniffer
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentParser
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.search.fetch.subphase.FetchSourceContext
import java.lang.Exception
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass

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
        val sniffer = Sniffer.builder(restHighLevelClient.lowLevelClient)
            .setSniffAfterFailureDelayMillis(sniffAfterFailureDelayMillis).setSniffIntervalMillis(sniffIntervalMillis)
            .build()
        sniffOnFailureListener.setSniffer(sniffer)
    }
    return restHighLevelClient
}

@Suppress("FunctionName")
@Deprecated(
    message = "Use the create function",
    replaceWith = ReplaceWith("create(host,port,https,user,password,useSniffer,sniffAfterFailureDelayMillis,sniffIntervalMillis)")
)
fun RestHighLevelClient(
    host: String = "localhost",
    port: Int = 9200,
    https: Boolean = false,
    user: String? = null,
    password: String? = null,
    useSniffer: Boolean = false,
    sniffAfterFailureDelayMillis: Int = 30000,
    sniffIntervalMillis: Int = 10000
): RestHighLevelClient =
    create(host, port, https, user, password, useSniffer, sniffAfterFailureDelayMillis, sniffIntervalMillis)

@Deprecated(
    "Use indexRepository",
    ReplaceWith("RestHighLevelClient.indexRepository(index,type,modelReaderAndWriter,refreshAllowed,readAlias,writeAlias,defaultRequestOptions)")
)
inline fun <reified T : Any> RestHighLevelClient.crudDao(
    index: String,
    modelReaderAndWriter: ModelReaderAndWriter<T> = JacksonModelReaderAndWriter.create<T>(),
    type: String = "_doc",
    readAlias: String = index,
    writeAlias: String = index,
    refreshAllowed: Boolean = false,
    defaultRequestOptions: RequestOptions = RequestOptions.DEFAULT
): IndexRepository<T> {
    return IndexRepository(
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
 * Create a new Index Repository. If you've used enterprise frameworks, you should be familiar with this pattern.
 *
 * This abstracts the business of telling the client which index to run against and serializing/deserializing documents in it.
 *
 */
inline fun <reified T : Any> RestHighLevelClient.indexRepository(
    index: String,
    modelReaderAndWriter: ModelReaderAndWriter<T> = JacksonModelReaderAndWriter.create<T>(),
    type: String = "_doc",
    readAlias: String = index,
    writeAlias: String = index,
    refreshAllowed: Boolean = false,
    fetchSourceContext: FetchSourceContext? = null,
    defaultRequestOptions: RequestOptions = RequestOptions.DEFAULT
): IndexRepository<T> {
    return IndexRepository(
        indexName = index,
        client = this,
        modelReaderAndWriter = modelReaderAndWriter,
        refreshAllowed = refreshAllowed,
        type = type,
        indexReadAlias = readAlias,
        indexWriteAlias = writeAlias,
        fetchSourceContext = fetchSourceContext,
        defaultRequestOptions = defaultRequestOptions

    )
}

inline fun <reified T : Any> RestHighLevelClient.asyncIndexRepository(
    index: String,
    modelReaderAndWriter: ModelReaderAndWriter<T> = JacksonModelReaderAndWriter.create<T>(),
    type: String = "_doc",
    readAlias: String = index,
    writeAlias: String = index,
    refreshAllowed: Boolean = false,
    fetchSourceContext: FetchSourceContext? = null,
    defaultRequestOptions: RequestOptions = RequestOptions.DEFAULT
): AsyncIndexRepository<T> {
    return AsyncIndexRepository(
        indexName = index,
        client = this,
        modelReaderAndWriter = modelReaderAndWriter,
        refreshAllowed = refreshAllowed,
        type = type,
        indexReadAlias = readAlias,
        indexWriteAlias = writeAlias,
        fetchSourceContext = fetchSourceContext,
        defaultRequestOptions = defaultRequestOptions

    )
}

// non reified version for Java users
fun <T : Any> RestHighLevelClient.createIndexRepository(
    index: String,
    modelReaderAndWriter: ModelReaderAndWriter<T>,
    type: String = "_doc",
    readAlias: String = index,
    writeAlias: String = index,
    refreshAllowed: Boolean = false,
    fetchSourceContext: FetchSourceContext? = null,
    defaultRequestOptions: RequestOptions = RequestOptions.DEFAULT
): IndexRepository<T> {
    return IndexRepository(
        indexName = index,
        client = this,
        modelReaderAndWriter = modelReaderAndWriter,
        refreshAllowed = refreshAllowed,
        type = type,
        indexReadAlias = readAlias,
        indexWriteAlias = writeAlias,
        fetchSourceContext = fetchSourceContext,
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
 * Get the next page of a scrolling search. Note, use the Repository to do scrolling searches and avoid manually doing these requests.
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
        ),
        requestOptions
    )
}

/**
 * Clear the scroll after you are done. If you use the Repository for scrolling searches, this is called for you.
 */
fun RestHighLevelClient.clearScroll(
    vararg scrollIds: String,
    requestOptions: RequestOptions = RequestOptions.DEFAULT
): ClearScrollResponse {
    val clearScrollRequest = ClearScrollRequest()
    scrollIds.forEach { clearScrollRequest.addScrollId(it) }
    return this.clearScroll(clearScrollRequest, requestOptions)
}

fun CreateIndexRequest.source(json: String): CreateIndexRequest = source(json, XContentType.JSON)

fun CreateIndexRequest.configure(
    generateMetaFields: Boolean = true,
    pretty: Boolean = false,
    block: IndexSettingsAndMappingsDSL.() -> Unit
) {
    source(
        IndexSettingsAndMappingsDSL.indexSettingsAndMappings(
            generateMetaFields = generateMetaFields,
            pretty = pretty,
            block = block
        )
    )
}

/**
 * Allows bypassing client side parsing & XContent processing of the request and sending json straight to
 * the search endpoint. This is useful with features supported in the server but not the Java client.
 *
 * Throws an IllegalStateException if Elasticsearch fails to return an OK status with details as to what went wrong.
 */
@Suppress("BlockingMethodInNonBlockingContext")
suspend fun RestHighLevelClient.searchAsyncDirect(
    endpoint: String,
    json: String,
    options: RequestOptions = RequestOptions.DEFAULT
): SearchResponse {
    val request = Request("POST", "$endpoint/_search")
    request.options = options
    request.setJsonEntity(json)
    return lowLevelClient.performRequestAsyncCustom(request) { response, content ->
        if (response.statusLine.statusCode == 200) {
            XContentType.JSON.xContent().createParser(
                NamedXContentRegistry.EMPTY, LOGGING_DEPRECATION_HANDLER, content
            ).use {
                SearchResponse.fromXContent(it)
            }
        } else {
            throw IllegalStateException(
                "elasticsearch returned ${response.statusLine}\n${
                    String(
                        content,
                        StandardCharsets.UTF_8
                    )
                }"
            )
        }
    }
}

/**
 * Suspending version of performRequestAsync that applies a converter to the raw response + any byte content
 * and returns the result.
 *
 * Because of the asynchronous resource processing and async call backs, it's best to extract the content from
 * the response here and avoid any issues with the input stream being closed.
 */
suspend fun <T> RestClient.performRequestAsyncCustom(
    request: Request,
    converter: (Response, ByteArray) -> T
): T {
    return suspendCancellableCoroutine { continuation ->
        val cancellable = this.performRequestAsync(request, object : ResponseListener {
            override fun onSuccess(response: Response) {
                // we have to consume content before resources are cleaned up; so consume it right away to avoid issues
                val content = response.entity.content.readBytes()
                continuation.resumeWith(Result.success(converter.invoke(response, content)))
            }

            override fun onFailure(exception: Exception) {
                continuation.resumeWith(Result.failure(exception))
            }
        })
        continuation.invokeOnCancellation {
            cancellable.cancel()
        }
    }
}