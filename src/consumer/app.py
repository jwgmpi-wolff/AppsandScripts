import os
import sys
import logging
import signal
from azure.servicebus import ServiceBusClient
from azure.storage.blob import BlobClient
from azure.identity import DefaultAzureCredential, ManagedIdentityCredential
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from azure.monitor.opentelemetry.exporter import AzureMonitorTraceExporter

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    stream=sys.stdout
)
logger = logging.getLogger(__name__)

_running = True

def signal_handler(signum, frame):
    global _running
    logger.info(f"Received signal {signum}, shutting down gracefully...")
    _running = False

def setup_telemetry():
    """Setup OpenTelemetry with Azure Monitor exporter if connection string is available"""
    connection_string = os.getenv('APPLICATIONINSIGHTS_CONNECTION_STRING')
    if not connection_string:
        logger.info("APPLICATIONINSIGHTS_CONNECTION_STRING not set, telemetry disabled")
        return None
    
    try:
        logger.info("Setting up OpenTelemetry with Azure Monitor")
        exporter = AzureMonitorTraceExporter(connection_string=connection_string)
        trace_provider = TracerProvider()
        trace_provider.add_span_processor(BatchSpanProcessor(exporter))
        trace.set_tracer_provider(trace_provider)
        return trace.get_tracer(__name__)
    except Exception as e:
        logger.error(f"Failed to setup telemetry: {e}")
        return None

def get_credential():
    """Get appropriate credential based on environment"""
    azure_client_id = os.getenv('AZURE_CLIENT_ID')
    if azure_client_id:
        logger.info(f"Using ManagedIdentityCredential with client_id: {azure_client_id}")
        return ManagedIdentityCredential(client_id=azure_client_id)
    else:
        logger.info("Using DefaultAzureCredential")
        return DefaultAzureCredential()

def process_message(msg, tracer, blob_account_url, blob_container_name, storage_credential):
    """Process a single message"""
    span_name = "process_message"
    
    if tracer:
        with tracer.start_as_current_span(span_name) as span:
            span.set_attribute("message.body", str(msg))
            _do_process_message(msg, blob_account_url, blob_container_name, storage_credential)
    else:
        _do_process_message(msg, blob_account_url, blob_container_name, storage_credential)

def _do_process_message(msg, blob_account_url, blob_container_name, storage_credential):
    """Actual message processing logic"""
    try:
        # Get the message body - handle different formats
        if hasattr(msg, 'body'):
            msg_body = msg.body
            if isinstance(msg_body, (bytes, bytearray)):
                body = msg_body.decode('utf-8')
            elif isinstance(msg_body, str):
                body = msg_body
            elif hasattr(msg_body, '__iter__') and not isinstance(msg_body, (str, bytes)):
                # Handle generators or iterables - join them
                body = b''.join(msg_body).decode('utf-8') if all(isinstance(x, bytes) for x in msg_body) else str(msg_body)
            else:
                body = str(msg_body)
        else:
            body = str(msg)
        
        logger.info(f"Processing: {body}")
        
        # Derive blob name
        blob_name = f"{body.lower()}.txt"
        blob_url = f"{blob_account_url.rstrip('/')}/{blob_container_name}/{blob_name}"
        
        # Upload blob
        blob_client = BlobClient.from_blob_url(blob_url, credential=storage_credential)
        blob_client.upload_blob(data=body.encode('utf-8'), overwrite=True)
        logger.info(f"Uploaded blob: {blob_name}")
        
    except Exception as e:
        logger.error(f"Error processing message: {e}", exc_info=True)
        raise

def main():
    global _running
    
    # Setup signal handlers
    signal.signal(signal.SIGTERM, signal_handler)
    signal.signal(signal.SIGINT, signal_handler)
    
    # Get environment variables
    service_bus_fqns = os.getenv('SERVICE_BUS_FQNS')
    queue_name = os.getenv('QUEUE_NAME')
    blob_account_url = os.getenv('BLOB_ACCOUNT_URL')
    blob_container_name = os.getenv('BLOB_CONTAINER_NAME')
    
    if not all([service_bus_fqns, queue_name, blob_account_url, blob_container_name]):
        logger.error("Missing required environment variables")
        sys.exit(1)
    
    logger.info("Starting consumer")
    logger.info(f"Service Bus: {service_bus_fqns}")
    logger.info(f"Queue: {queue_name}")
    logger.info(f"Blob Storage: {blob_account_url}")
    logger.info(f"Container: {blob_container_name}")
    
    # Setup telemetry
    tracer = setup_telemetry()
    
    # Get credentials
    sb_credential = get_credential()
    storage_credential = get_credential()
    
    try:
        # Create clients
        servicebus_client = ServiceBusClient(
            fully_qualified_namespace=service_bus_fqns,
            credential=sb_credential
        )
        
        # Main processing loop
        with servicebus_client.get_queue_receiver(
            queue_name,
            max_wait_time=10
        ) as receiver:
            logger.info("Connected to Service Bus, waiting for messages...")
            
            while _running:
                try:
                    messages = receiver.receive_messages(max_message_count=1, max_wait_time=10)
                    if not messages:
                        continue
                    
                    for msg in messages:
                        try:
                            process_message(msg, tracer, blob_account_url, blob_container_name, storage_credential)
                            receiver.complete_message(msg)
                            logger.info("Message completed")
                        except Exception as e:
                            logger.error(f"Failed to process message: {e}", exc_info=True)
                            receiver.abandon_message(msg)
                            
                except Exception as e:
                    logger.error(f"Error receiving messages: {e}", exc_info=True)
                    if not _running:
                        break
        
        logger.info("Consumer shutdown complete")
        
    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
        sys.exit(1)
    finally:
        # Flush any pending spans
        if tracer:
            try:
                trace.get_tracer_provider().force_flush(timeout_millis=30000)
            except Exception as e:
                logger.warning(f"Error flushing traces: {e}")

if __name__ == '__main__':
    main()
