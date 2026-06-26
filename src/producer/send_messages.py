import os
import sys
from azure.servicebus import ServiceBusClient, ServiceBusMessage
from azure.identity import DefaultAzureCredential
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def send_messages():
    try:
        service_bus_fqns = os.getenv('SERVICE_BUS_FQNS')
        queue_name = os.getenv('QUEUE_NAME')
        
        if not service_bus_fqns or not queue_name:
            raise ValueError("SERVICE_BUS_FQNS and QUEUE_NAME environment variables are required")
        
        logger.info(f"Connecting to Service Bus: {service_bus_fqns}")
        logger.info(f"Queue: {queue_name}")
        
        credential = DefaultAzureCredential()
        client = ServiceBusClient(fully_qualified_namespace=service_bus_fqns, credential=credential)
        
        with client.get_queue_sender(queue_name) as sender:
            for i in range(1, 11):
                message_body = f"Message{i}"
                message = ServiceBusMessage(message_body)
                sender.send_messages(message)
                logger.info(f"Sent: {message_body}")
        
        logger.info("All 10 messages sent successfully")
        
    except Exception as e:
        logger.error(f"Error: {e}", exc_info=True)
        sys.exit(1)

if __name__ == '__main__':
    send_messages()
