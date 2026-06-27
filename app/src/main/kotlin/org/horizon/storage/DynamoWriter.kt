package org.horizon.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import java.net.URI

object DynamoWriter {

    private val client = DynamoDbClient.builder()
        .endpointOverride(URI.create("http://localhost:4566"))
        .region(Region.EU_CENTRAL_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")
            )
        )
        .build()

    fun writePolarityScore(
        ticker: String,
        timestamp: String,
        polarityScore: Double,
        articleCount: Int
    ) {
        val item = mapOf(
            "ticker"         to AttributeValue.builder().s(ticker).build(),
            "timestamp"      to AttributeValue.builder().s(timestamp).build(),
            "polarity_score" to AttributeValue.builder().n(polarityScore.toString()).build(),
            "article_count"  to AttributeValue.builder().n(articleCount.toString()).build(),
            "ttl"            to AttributeValue.builder().n(
                ((System.currentTimeMillis() / 1000) + 172800).toString()
            ).build()
        )

        val request = PutItemRequest.builder()
            .tableName("horizon-sentiment")
            .item(item)
            .build()

        client.putItem(request)
        println("  ✓ wrote $ticker (${"%.3f".format(polarityScore)}) to DynamoDB")
    }
}