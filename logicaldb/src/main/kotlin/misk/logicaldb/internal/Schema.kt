package misk.logicaldb.internal

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperTableModel
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMappingException
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.KeyType.HASH
import com.amazonaws.services.dynamodbv2.model.KeyType.RANGE
import misk.logicaldb.ForIndex
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.withNullability

internal class Schema(
  private val keyTypeFactory: KeyType.Factory,
  private val itemTypeFactory: ItemType.Factory,
  private val rawItemTypeFactory: RawItemType.Factory
) {
  private val keyTypes = mutableMapOf<KClass<*>, KeyType>()
  private val itemTypes = mutableMapOf<KClass<*>, ItemType>()
  private val rawItemTypes = mutableMapOf<KClass<*>, RawItemType>()

  fun getKey(type: KClass<*>): KeyType? = keyTypes[type]

  fun getItem(type: KClass<*>): ItemType? = itemTypes[type]

  fun getRawItem(type: KClass<*>): RawItemType? = rawItemTypes[type]

  fun resolveEnclosingItemType(type: KClass<*>): ItemType? {
    val schema = getLogicalType(type) ?: return null
    return when (schema) {
      is KeyType -> getItem(schema.itemType)
      is ItemType -> schema
      else -> null
    }
  }

  fun resolveEnclosingRawItemType(type: KClass<*>): RawItemType? {
    val schema = getLogicalType(type) ?: return null
    return when (schema) {
      is KeyType -> getRawItem(getItem(schema.itemType)!!.rawItemType)
      is ItemType -> getRawItem(schema.rawItemType)
      is RawItemType -> schema
    }
  }

  private fun getLogicalType(type: KClass<*>): LogicalType? = getKey(type)
      ?: getItem(type) ?: getRawItem(type)

  fun addKey(keyType: KClass<*>, itemType: KClass<*>): KeyType {
    val existingKeyType = keyTypes.getOrPut(keyType) {
      val itemType = requireNotNull(getItem(itemType))
      keyTypeFactory.create(keyType, itemType, requireNotNull(getRawItem(itemType.rawItemType)))
    }
    require(existingKeyType.itemType == itemType) {
      "Cannot bind $keyType to $itemType because it is already bound to ${existingKeyType.itemType}"
    }
    return existingKeyType
  }

  fun addItem(itemType: KClass<*>, rawItemType: KClass<*>): ItemType {
    val existingItemType = itemTypes.getOrPut(itemType) {
      itemTypeFactory.create(itemType, requireNotNull(getRawItem(rawItemType)))
    }
    require(existingItemType.rawItemType == rawItemType) {
      "Cannot bind $itemType to $rawItemType because it is already bound to ${existingItemType.rawItemType}"
    }
    return existingItemType
  }

  fun addRawItem(rawItemType: KClass<*>): RawItemType {
    return rawItemTypes.getOrPut(rawItemType) {
      rawItemTypeFactory.create(rawItemType)
    }
  }
}

internal sealed class LogicalType {
  abstract val codec: Codec<Any, Any>
}

internal data class KeyType(
  override val codec: Codec<Any, Any>,
  val type: KClass<*>,
  val itemType: KClass<*>,
  val attributeNames: Set<String>,
  val secondaryIndexName: String?
) : LogicalType() {
  class Factory(
    private val codecFactory: ReflectionCodec.Factory
  ) {
    fun create(keyType: KClass<*>, itemType: ItemType, rawItemType: RawItemType): KeyType {
      val attributeNames = mutableSetOf<String>()
      for (property in keyType.memberProperties) {
        val attribute = requireNotNull(
            itemType.attributes[property.name]) { "Expect ${property.name}, required by $keyType, to be declared in ${itemType.type}" }
        val expectedReturnType =
            requireNotNull(itemType.attributes[property.name]?.returnType).withNullability(false)
        val actualReturnType = property.returnType.withNullability(false)
        require(
            actualReturnType == expectedReturnType) { "Expect the return type of $keyType.${property.name} to be $expectedReturnType but was $actualReturnType" }
        require(
            property.findAnnotation<AttributeAnnotation>() == null) { "Please move Attribute annotation from $keyType.${property.name} to ${itemType.type}.${property.name}" }
        attributeNames.addAll(attribute.names)
      }
      for ((attributeName, _) in itemType.attributePrefixes) {
        attributeNames.add(attributeName)
      }
      val primaryIndex = itemType.primaryIndex
      require(attributeNames.contains(primaryIndex.hashKeyName))
      if (primaryIndex.rangeKeyName != null) {
        require(attributeNames.contains(
            primaryIndex.rangeKeyName)) { "Expect $keyType to have property ${primaryIndex.rangeKeyName}" }
      }
      val secondaryIndexName = keyType.findAnnotation<ForIndex>()?.name
      if (secondaryIndexName != null) {
        val secondaryIndex = requireNotNull(itemType.secondaryIndexes[secondaryIndexName])
        require(attributeNames.contains(
            secondaryIndex.hashKeyName)) { "Expect $keyType to have property ${secondaryIndex.hashKeyName}" }
        require(attributeNames.contains(
            secondaryIndex.rangeKeyName)) { "Expect $keyType to have property ${secondaryIndex.rangeKeyName}" }
      }
      return KeyType(
          codecFactory.create(keyType, itemType.attributes, rawItemType),
          keyType,
          itemType.type,
          attributeNames.toSet(),
          secondaryIndexName)
    }
  }
}

internal data class ItemType(
  override val codec: Codec<Any, Any>,
  val type: KClass<*>,
  val rawItemType: KClass<*>,
  val attributes: Map<String, Attribute>,
  val primaryIndex: PrimaryIndex,
  val secondaryIndexes: Map<String, SecondaryIndex>
) : LogicalType() {

  val attributeNames: Set<String>
    get() = attributes.values.flatMap { it.names }.toSet()

  val attributePrefixes: Map<String, String>
    get() = attributes.values
        .filter { attribute -> attribute.prefix.isNotEmpty() }
        .flatMap { attribute -> attribute.names.map { it to attribute.prefix } }
        .toMap()

  data class Attribute(
    val names: Set<String>,
    val prefix: String,
    val returnType: KType
  )

  data class PrimaryIndex(val hashKeyName: String, val rangeKeyName: String?)

  data class SecondaryIndex(val name: String, val hashKeyName: String, val rangeKeyName: String)

  class Factory(
    private val codecFactory: ReflectionCodec.Factory
  ) {

    fun create(itemType: KClass<*>, rawItemType: RawItemType): ItemType {
      val constructor = requireNotNull(
          itemType.primaryConstructor) { "$itemType must have a primary constructor" }
      val parametersByName = constructor.parameters.associateBy { requireNotNull(it.name) }
      val primaryIndex = findPrimaryIndex(rawItemType)
      val attributes = findAttributes(itemType, parametersByName, rawItemType, primaryIndex)
      return ItemType(
          codecFactory.create(itemType, attributes, rawItemType),
          itemType,
          rawItemType.type,
          attributes,
          primaryIndex,
          findSecondaryIndexes(rawItemType))
    }

    private fun findAttributes(
      itemType: KClass<*>,
      constructorParameters: Map<String, KParameter>,
      rawItemType: RawItemType,
      primaryIndex: PrimaryIndex
    ): Map<String, Attribute> {
      val attributes = mutableMapOf<String, Attribute>()
      for (property in itemType.memberProperties) {
        val annotation: AttributeAnnotation? = property.findAnnotation()
            ?: constructorParameters[property.name]?.findAnnotation()
        val rawItemPropertyNames = annotation?.annotatedNames ?: setOf(property.name)
        for (rawItemPropertyName in rawItemPropertyNames) {
          require(rawItemType.hasProperty(rawItemPropertyName)) {
            "Expect $rawItemPropertyName, required by $itemType, to be declared in ${rawItemType.type}"
          }
        }
        val prefix = annotation?.prefix ?: ""
        attributes[property.name] = Attribute(rawItemPropertyNames, prefix, property.returnType)
      }
      val attributesByName =
          attributes.values.flatMap { attribute -> attribute.names.map { it to attribute } }.toMap()
      require(attributesByName.contains(primaryIndex.hashKeyName)) {
        "Expect $itemType to map to ${rawItemType.type}'s hash key ${primaryIndex.hashKeyName}"
      }
      if (primaryIndex.rangeKeyName != null) {
        require(attributesByName.contains(primaryIndex.rangeKeyName)) {
          "Expect $itemType to map to ${rawItemType.type}'s range key ${primaryIndex.rangeKeyName}"
        }
        require(attributesByName[primaryIndex.rangeKeyName]!!.prefix.isNotEmpty()) {
          "Expect $itemType.${primaryIndex.rangeKeyName} to be annotated with a prefix"
        }
      }
      return attributes.toMap()
    }

    private fun findPrimaryIndex(rawItemType: RawItemType): PrimaryIndex {
      return PrimaryIndex(
          rawItemType.tableModel.hashKey<Any>().name(),
          rawItemType.tableModel.rangeKeyIfExists<Any>()?.name())
    }

    private fun findSecondaryIndexes(
      rawItemType: RawItemType
    ): Map<String, SecondaryIndex> {
      val secondaryIndexes = mutableMapOf<String, SecondaryIndex>()
      val globalSecondaryIndexes = rawItemType.tableModel.globalSecondaryIndexes() ?: emptyList()
      for (globalSecondaryIndex in globalSecondaryIndexes) {
        val indexName = globalSecondaryIndex.indexName
        val keys = globalSecondaryIndex.keySchema.associateBy { it.keyType }
        val hashKeyName = requireNotNull(keys[HASH.toString()]).attributeName
        val rangeKeyName = requireNotNull(keys[RANGE.toString()]).attributeName
        secondaryIndexes[indexName] = SecondaryIndex(indexName, hashKeyName, rangeKeyName)
      }
      val localSecondaryIndexes = rawItemType.tableModel.localSecondaryIndexes() ?: emptyList()
      for (localSecondaryIndex in localSecondaryIndexes) {
        val indexName = localSecondaryIndex.indexName
        val keys = localSecondaryIndex.keySchema.associateBy { it.keyType }
        val hashKeyName = requireNotNull(keys[HASH.toString()]).attributeName
        val rangeKeyName = requireNotNull(keys[RANGE.toString()]).attributeName
        secondaryIndexes[indexName] = SecondaryIndex(indexName, hashKeyName, rangeKeyName)
      }
      return secondaryIndexes.toMap()
    }

    private val AttributeAnnotation.annotatedNames: Set<String>?
      get() = when {
        names.isNotEmpty() -> {
          check(name.isEmpty()) { "Attribute annotation is ambiguous. name: $name, names: $names" }
          names.toSet()
        }
        name.isNotEmpty() -> {
          setOf(name)
        }
        else -> {
          null
        }
      }

    private fun RawItemType.hasProperty(name: String): Boolean {
      return try {
        tableModel.field<Any>(name)
        true
      } catch (e: DynamoDBMappingException) {
        false
      }
    }
  }
}

internal data class RawItemType(
  override val codec: Codec<Any, Any>,
  val tableName: String,
  val type: KClass<Any>,
  val tableModel: DynamoDBMapperTableModel<Any>
) : LogicalType() {

  fun key(rawItem: Any): RawItemKey {
    val keyAttributes = tableModel.convert(rawItem)
    val hashKey = keyAttributes[tableModel.hashKey<Any>().name()]!!
    val rangeKey = keyAttributes[tableModel.rangeKeyIfExists<Any>()?.name()]
    return RawItemKey(tableName, hashKey, rangeKey)
  }

  data class RawItemKey(val tableName: String, val hashKey: AttributeValue, val rangeKey: AttributeValue?)

  class Factory(
    private val dynamoDbMapper: DynamoDBMapper,
    private val config: DynamoDBMapperConfig
  ) {
    fun create(rawItemType: KClass<*>): RawItemType {
      return RawItemType(
          IdentityCodec,
          config.tableNameResolver.getTableName(rawItemType.java, config),
          rawItemType as KClass<Any>,
          dynamoDbMapper.getTableModel(rawItemType.java) as DynamoDBMapperTableModel<Any>)
    }
  }
}

typealias AttributeAnnotation = misk.logicaldb.Attribute
