package org.horizon.storage

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import java.net.URI

object DynamoWriter : PolarityStore {

    private val logger = LoggerFactory.getLogger(DynamoWriter::class.java)

    private const val TABLE_NAME  = "horizon-sentiment"
    private const val TTL_SECONDS = 48 * 60 * 60L

    private val client: DynamoDbClient = run {
        val endpoint = System.getenv("DYNAMODB_ENDPOINT")
        val region   = Region.of(System.getenv("AWS_REGION") ?: "us-east-1")
        val builder  = DynamoDbClient.builder().region(region)
        if (endpoint != null) {
            builder
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")
                    )
                )
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create())
        }
        builder.build()
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { client.close() })
    }

    override fun writePolarityScore(
        ticker: String,
        timestamp: String,
        score: Double,
        articleCount: Int
    ) {
        val item = mapOf(
            "ticker"         to AttributeValue.builder().s(ticker).build(),
            "timestamp"      to AttributeValue.builder().s(timestamp).build(),
            "polarity_score" to AttributeValue.builder().n(score.toString()).build(),
            "article_count"  to AttributeValue.builder().n(articleCount.toString()).build(),
            "ttl"            to AttributeValue.builder().n(
                ((System.currentTimeMillis() / 1000) + TTL_SECONDS).toString()
            ).build()
        )

        client.putItem(
            PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build()
        )
        logger.info("Wrote {} ({}) to DynamoDB", ticker, "%.3f".format(score))
    }
}
