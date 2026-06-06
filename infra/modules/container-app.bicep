param location string
param namePrefix string
param environmentId string
param identityId string
param acrLoginServer string
@secure()
param appInsightsConnectionString string
param serviceBusNamespaceName string
param queueName string
param identityClientId string
param usePlaceholderImage bool = true

var containerAppName = '${namePrefix}-consumer'
var imageName = usePlaceholderImage ? 'mcr.microsoft.com/k8se/quickstart:latest' : '${acrLoginServer}/consumer:latest'

resource containerApp 'Microsoft.App/containerApps@2024-10-02-preview' = {
  name: containerAppName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identityId}': {}
    }
  }
  properties: {
    managedEnvironmentId: environmentId
    configuration: {
      ingress: {
        external: false
      }
      registries: [
        {
          server: acrLoginServer
          identity: identityId
        }
      ]
      secrets: [
        {
          name: 'appinsights-cs'
          value: appInsightsConnectionString
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'consumer'
          image: imageName
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
          env: [
            {
              name: 'SERVICE_BUS_FQNS'
              value: '${serviceBusNamespaceName}.servicebus.windows.net'
            }
            {
              name: 'QUEUE_NAME'
              value: queueName
            }
            {
              name: 'AZURE_CLIENT_ID'
              value: identityClientId
            }
            {
              name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
              secretRef: 'appinsights-cs'
            }
          ]
        }
      ]
      scale: {
        minReplicas: 0
        maxReplicas: 10
        rules: [
          {
            name: 'servicebus-queue-length'
            custom: {
              type: 'azure-servicebus'
              metadata: {
                namespace: serviceBusNamespaceName
                queueName: queueName
                messageCount: '1'
              }
              auth: []
              identity: identityId
            }
          }
        ]
      }
    }
  }
}

output containerAppName string = containerApp.name
output containerAppId string = containerApp.id
