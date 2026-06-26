// Bicep template for deploying infrastructure for Workbook Audit Container App
// Deploys: AKS cluster, Container Registry, Storage Account, Key Vault, Managed Identity

param location string = resourceGroup().location
param environment string = 'prod'
param projectName string = 'workbookaudit'

// Tags for all resources
param commonTags object = {
  environment: environment
  project: projectName
  managedBy: 'Bicep'
  createdDate: utcNow('u')
}

// AKS Configuration
param aksClusterName string = '${projectName}-aks-${environment}'
param aksNodeCount int = 2
param aksVmSize string = 'Standard_D2s_v3'
param aksOSType string = 'Linux'

// Container Registry
param acrName string = replace('${projectName}acr${environment}', '-', '')
param acrSku string = 'Standard'

// Storage Account
param storageAccountName string = replace('${projectName}sa${environment}', '-', '')
param storageSku string = 'Standard_LRS'

// Key Vault
param keyVaultName string = '${projectName}-kv-${environment}'

// Managed Identity
param identityName string = '${projectName}-identity-${environment}'

// Variables
var vnetName = '${projectName}-vnet-${environment}'
var vnetAddressPrefix = '10.0.0.0/16'
var subnetName = '${projectName}-subnet-${environment}'
var subnetAddressPrefix = '10.0.1.0/24'
var nsgName = '${projectName}-nsg-${environment}'
var storageContainerName = 'graphqueriessamples'

// ===== Network Security Group =====
resource nsg 'Microsoft.Network/networkSecurityGroups@2023-04-01' = {
  name: nsgName
  location: location
  tags: commonTags
  properties: {
    securityRules: [
      {
        name: 'AllowHTTPS'
        properties: {
          protocol: 'Tcp'
          sourcePortRange: '*'
          destinationPortRange: '443'
          sourceAddressPrefix: '*'
          destinationAddressPrefix: '*'
          access: 'Allow'
          priority: 100
          direction: 'Inbound'
        }
      }
      {
        name: 'AllowHTTP'
        properties: {
          protocol: 'Tcp'
          sourcePortRange: '*'
          destinationPortRange: '80'
          sourceAddressPrefix: '*'
          destinationAddressPrefix: '*'
          access: 'Allow'
          priority: 110
          direction: 'Inbound'
        }
      }
    ]
  }
}

// ===== Virtual Network =====
resource vnet 'Microsoft.Network/virtualNetworks@2023-04-01' = {
  name: vnetName
  location: location
  tags: commonTags
  properties: {
    addressSpace: {
      addressPrefixes: [
        vnetAddressPrefix
      ]
    }
    subnets: [
      {
        name: subnetName
        properties: {
          addressPrefix: subnetAddressPrefix
          networkSecurityGroup: {
            id: nsg.id
          }
          serviceEndpoints: [
            {
              service: 'Microsoft.Storage'
            }
            {
              service: 'Microsoft.KeyVault'
            }
          ]
        }
      }
    ]
  }
}

// ===== Managed Identity =====
resource managedIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: identityName
  location: location
  tags: commonTags
}

// ===== Container Registry =====
resource acr 'Microsoft.ContainerRegistry/registries@2023-06-01-preview' = {
  name: acrName
  location: location
  tags: commonTags
  sku: {
    name: acrSku
  }
  properties: {
    adminUserEnabled: true
    publicNetworkAccess: 'Enabled'
    networkRuleBypassOptions: 'AzureServices'
  }
}

// Role assignment: Managed Identity can pull from ACR
resource acrPullRole 'Microsoft.Authorization/roleAssignments@2023-04-01-preview' = {
  scope: acr
  name: guid(acr.id, managedIdentity.id, 'acrpull')
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-6e2dd633aa60') // AcrPull role
    principalId: managedIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// ===== Storage Account =====
resource storageAccount 'Microsoft.Storage/storageAccounts@2023-01-01' = {
  name: storageAccountName
  location: location
  tags: commonTags
  kind: 'BlobStorage'
  sku: {
    name: storageSku
  }
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    defaultToOAuthAuthentication: true
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      defaultAction: 'Allow'
      bypass: 'AzureServices'
      virtualNetworkRules: [
        {
          id: '${vnet.id}/subnets/${subnetName}'
          action: 'Allow'
        }
      ]
    }
  }
}

// Storage container for audit results
resource storageContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-01-01' = {
  name: '${storageAccount.name}/default/${storageContainerName}'
  properties: {
    publicAccess: 'None'
  }
}

// Role assignment: Managed Identity can write to storage
resource storageContributorRole 'Microsoft.Authorization/roleAssignments@2023-04-01-preview' = {
  scope: storageAccount
  name: guid(storageAccount.id, managedIdentity.id, 'storagecontributor')
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '17d1049b-9a84-46fb-a7d3-cd100f7296ab') // Storage Blob Data Reader
    principalId: managedIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// ===== Key Vault =====
resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: keyVaultName
  location: location
  tags: commonTags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    accessPolicies: [
      {
        tenantId: subscription().tenantId
        objectId: managedIdentity.properties.principalId
        permissions: {
          secrets: [
            'get'
            'list'
          ]
          keys: []
          certificates: []
        }
      }
    ]
    networkAcls: {
      defaultAction: 'Allow'
      bypass: 'AzureServices'
      virtualNetworkRules: [
        {
          id: '${vnet.id}/subnets/${subnetName}'
          action: 'Allow'
        }
      ]
    }
  }
}

// ===== AKS Cluster =====
resource aksCluster 'Microsoft.ContainerService/managedClusters@2023-09-01' = {
  name: aksClusterName
  location: location
  tags: commonTags
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    kubernetesVersion: '1.27'
    dnsPrefix: aksClusterName
    enableRBAC: true
    networkProfile: {
      networkPlugin: 'azure'
      serviceCidr: '10.1.0.0/16'
      dnsServiceIP: '10.1.0.10'
      dockerBridgeCidr: '172.17.0.1/16'
      loadBalancerSku: 'standard'
      networkPolicy: 'azure'
    }
    agentPoolProfiles: [
      {
        name: 'systempool'
        count: aksNodeCount
        vmSize: aksVmSize
        osType: aksOSType
        mode: 'System'
        vnetSubnetID: '${vnet.id}/subnets/${subnetName}'
        maxPods: 110
        type: 'VirtualMachineScaleSets'
        availabilityZones: [
          '1'
          '2'
          '3'
        ]
        osDiskSizeGB: 128
        osDiskType: 'Managed'
      }
    ]
    addonProfiles: {
      httpApplicationRouting: {
        enabled: false
      }
      monitoring: {
        enabled: true
        config: {
          logAnalyticsWorkspaceResourceID: logAnalyticsWorkspace.id
        }
      }
    }
  }
}

// ===== Log Analytics Workspace (for AKS monitoring) =====
resource logAnalyticsWorkspace 'Microsoft.OperationalInsights/workspaces@2022-10-01' = {
  name: '${projectName}-law-${environment}'
  location: location
  tags: commonTags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
  }
}

// ===== Outputs =====
output aksClusterId string = aksCluster.id
output aksClusterName string = aksCluster.name
output acrLoginServer string = acr.properties.loginServer
output acrName string = acr.name
output storageAccountId string = storageAccount.id
output storageAccountName string = storageAccount.name
output storageContainerName string = storageContainerName
output keyVaultId string = keyVault.id
output keyVaultName string = keyVault.name
output managedIdentityId string = managedIdentity.id
output managedIdentityClientId string = managedIdentity.properties.clientId
output managedIdentityPrincipalId string = managedIdentity.properties.principalId
output vnetId string = vnet.id
output logAnalyticsWorkspaceId string = logAnalyticsWorkspace.id
