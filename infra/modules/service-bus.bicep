param location string
param namePrefix string
param identityPrincipalId string

var namespaceName = '${take(namePrefix, 10)}ns${substring(uniqueString(resourceGroup().id), 0, 6)}'
var queueName = 'messages'

resource serviceBusNamespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: namespaceName
  location: location
  sku: {
    name: 'Standard'
    tier: 'Standard'
  }
  properties: {
    disableLocalAuth: true
    minimumTlsVersion: '1.2'
  }
}

resource queue 'Microsoft.ServiceBus/namespaces/queues@2022-10-01-preview' = {
  parent: serviceBusNamespace
  name: queueName
  properties: {
    lockDuration: 'PT1M'
    maxDeliveryCount: 5
    defaultMessageTimeToLive: 'P1D'
    deadLetteringOnMessageExpiration: true
  }
}

// Azure Service Bus Data Receiver
resource rbacReceiver 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: serviceBusNamespace
  name: guid(serviceBusNamespace.id, identityPrincipalId, '4f6d3b9b-027b-4f4c-9142-0e5a2a2247e0')
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4f6d3b9b-027b-4f4c-9142-0e5a2a2247e0')
    principalId: identityPrincipalId
    principalType: 'ServicePrincipal'
  }
}

// Azure Service Bus Data Sender
resource rbacSender 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: serviceBusNamespace
  name: guid(serviceBusNamespace.id, identityPrincipalId, '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39')
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39')
    principalId: identityPrincipalId
    principalType: 'ServicePrincipal'
  }
}

// Azure Service Bus Data Owner (required for KEDA scaler)
resource rbacOwner 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: serviceBusNamespace
  name: guid(serviceBusNamespace.id, identityPrincipalId, '090c5cfd-751d-490a-894a-3ce6f1109419')
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '090c5cfd-751d-490a-894a-3ce6f1109419')
    principalId: identityPrincipalId
    principalType: 'ServicePrincipal'
  }
}

output namespaceFqns string = '${serviceBusNamespace.name}.servicebus.windows.net'
output namespaceName string = serviceBusNamespace.name
output namespaceId string = serviceBusNamespace.id
output queueNameOutput string = queueName
