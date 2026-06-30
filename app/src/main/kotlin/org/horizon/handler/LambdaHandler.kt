package org.horizon.handler

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.SQSEvent

class LambdaHandler : RequestHandler<SQSEvent, Unit> {
    override fun handleRequest(event: SQSEvent, context: Context) {
        TODO("Phase 2C")
    }
}
