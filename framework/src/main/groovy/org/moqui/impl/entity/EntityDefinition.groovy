/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.entity

import java.sql.Timestamp

import org.apache.commons.collections.set.ListOrderedSet
import org.apache.commons.collections.map.ListOrderedMap

import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityException
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.FieldToFieldCondition

public class EntityDefinition {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EntityDefinition.class)

    protected EntityFacadeImpl efi
    protected String internalEntityName
    protected String fullEntityName
    protected Node internalEntityNode
    protected Map<String, Node> fieldNodeMap = new HashMap()
    protected Map<String, Node> relationshipNodeMap = new HashMap()
    protected Map<String, String> columnNameMap = new HashMap()
    protected List<String> pkFieldNameList = null
    protected List<String> allFieldNameList = null

    protected Boolean isView = null
    protected Boolean needsAuditLogVal = null
    protected Boolean needsEncryptVal = null

    protected List<Node> expandedRelationshipList = null

    EntityDefinition(EntityFacadeImpl efi, Node entityNode) {
        this.efi = efi
        this.internalEntityName = (entityNode."@entity-name").intern()
        this.fullEntityName = (entityNode."@package-name" + "." + this.internalEntityName).intern()
        this.internalEntityNode = entityNode

        if (isViewEntity()) {
            // if this is a view-entity, expand the alias-all elements into alias elements here
            this.expandAliasAlls()
            // set @type, set is-pk on all alias Nodes if the related field is-pk
            for (Node aliasNode in entityNode."alias") {
                Node memberEntity = (Node) entityNode."member-entity".find({ it."@entity-alias" == aliasNode."@entity-alias" })
                if (memberEntity == null) throw new EntityException("Could not find member-entity with entity-alias [${aliasNode."@entity-alias"}] in view-entity [${internalEntityName}]")
                EntityDefinition memberEd = this.efi.getEntityDefinition(memberEntity."@entity-name")
                String fieldName = aliasNode."@field" ?: aliasNode."@name"
                Node fieldNode = memberEd.getFieldNode(fieldName)
                if (fieldNode == null) throw new EntityException("In view-entity [${internalEntityName}] alias [${aliasNode."@name"}] referred to field [${fieldName}] that does not exist on entity [${memberEd.internalEntityName}].")
                aliasNode.attributes().put("type", fieldNode.attributes().get("type"))
                if (fieldNode."@is-pk" == "true") aliasNode."@is-pk" = "true"
            }
        } else {
            if (this.internalEntityNode."@no-update-stamp" != "true") {
                // automatically add the lastUpdatedStamp field
                this.internalEntityNode.appendNode("field", [name:"lastUpdatedStamp", type:"date-time"])
            }
        }
    }

    String getEntityName() { return this.internalEntityName }
    String getFullEntityName() { return this.fullEntityName }

    Node getEntityNode() { return this.internalEntityNode }

    boolean isViewEntity() {
        if (isView == null) isView = (this.internalEntityNode.name() == "view-entity")
        return isView
    }
    boolean hasFunctionAlias() { return isViewEntity() && this.internalEntityNode."alias".find({ it."@function" }) }

    String getDefaultDescriptionField() {
        ListOrderedSet nonPkFields = getFieldNames(false, true)
        // find the first *Name
        for (String fn in nonPkFields)
            if (fn.endsWith("Name")) return fn

        // no name? try literal description
        if (isField("description")) return "description"

        // no description? just use the first non-pk field
        return nonPkFields.get(0)
    }

    boolean needsAuditLog() {
        if (needsAuditLogVal != null) return needsAuditLogVal
        needsAuditLogVal = false
        for (Node fieldNode in getFieldNodes(true, true)) {
            if (fieldNode."@enable-audit-log" == "true") needsAuditLogVal = true
        }
        return needsAuditLogVal
    }

    boolean needsEncrypt() {
        if (needsEncryptVal != null) return needsEncryptVal
        needsEncryptVal = false
        for (Node fieldNode in getFieldNodes(true, true)) {
            if (fieldNode."@encrypt" == "true") needsEncryptVal = true
        }
        return needsEncryptVal
    }

    Node getFieldNode(String fieldName) {
        Node fn = fieldNodeMap.get(fieldName)
        if (fn != null) return fn
        String nodeName = this.isViewEntity() ? "alias" : "field"
        fn = (Node) this.internalEntityNode[nodeName].find({ it.@name == fieldName })
        fieldNodeMap.put(fieldName, fn)
        return fn
    }

    Node getRelationshipNode(String relationshipName) {
        Node relNode = relationshipNodeMap.get(relationshipName)
        if (relNode != null) return relNode

        relNode = (Node) this.internalEntityNode."relationship"
                .find({ ((it."@title" ?: "") + it."@related-entity-name") == relationshipName ||
                    ((it."@title" ?: "") + "#" + it."@related-entity-name") == relationshipName})

        // handle automatic reverse-many nodes (based on one node coming the other way)
        if (relNode == null) {
            // see if there is an entity matching the relationship name that has a relationship coming this way
            EntityDefinition ed = null
            try {
                ed = efi.getEntityDefinition(relationshipName)
            } catch (EntityException e) {
                // probably means not a valid entity name, which may happen a lot since we're checking here to see, so just ignore
            }
            if (ed != null) {
                // don't call ed.getRelationshipNode(), may result in infinite recursion
                Node reverseRelNode = (Node) ed.internalEntityNode."relationship".find(
                        { it."@related-entity-name" == this.internalEntityName && (it."@type" == "one" || it."@type" == "one-nofk") })
                if (reverseRelNode != null) {
                    Map keyMap = ed.getRelationshipExpandedKeyMap(reverseRelNode)
                    Node newRelNode = this.internalEntityNode.appendNode("relationship",
                            ["related-entity-name":relationshipName, "type":"many"])
                    for (Map.Entry keyEntry in keyMap) {
                        // add a key-map with the reverse fields
                        newRelNode.appendNode("key-map", ["field-name":keyEntry.value, "related-field-name":keyEntry.key])
                    }
                    relNode = newRelNode
                }
            }
        }

        relationshipNodeMap.put(relationshipName, relNode)
        return relNode
    }

    Map getRelationshipExpandedKeyMap(Node relationship) {
        ListOrderedMap eKeyMap = new ListOrderedMap()
        EntityDefinition relEd = this.efi.getEntityDefinition(relationship."@related-entity-name")
        if (!relEd) throw new EntityException("Could not find entity [${relationship."@related-entity-name"}] referred to in a relationship in entity [${internalEntityName}]")
        if (!relationship."key-map" && ((String) relationship."@type").startsWith("one")) {
            // go through pks of related entity, assume field names match
            for (String pkFieldName in relEd.getPkFieldNames()) eKeyMap.put(pkFieldName, pkFieldName)
        } else {
            for (Node keyMap in relationship."key-map") {
                String relFn = keyMap."@related-field-name" ?: keyMap."@field-name"
                if (!relEd.isField(relFn) && ((String) relationship."@type").startsWith("one")) {
                    List<String> pks = relEd.getPkFieldNames()
                    if (pks.size() == 1) relFn = pks.get(0)
                    // if we don't match these constraints and get this default we'll get an error later...
                }
                eKeyMap.put(keyMap."@field-name", relFn)
            }
        }
        return eKeyMap
    }

    List<Map> getRelationshipsInfo(Map valueSource, boolean dependentsOnly) {
        if (!this.expandedRelationshipList) {
            // make sure this is done before as this isn't done by default
            efi.createAllAutoReverseManyRelationships()
            this.expandedRelationshipList = this.internalEntityNode."relationship"
        }

        List<Map> infoList = new ArrayList()
        for (Node relNode in this.expandedRelationshipList) {
            // for now dependent entities are just those of type many
            if (dependentsOnly && relNode."@is-one-reverse" != "true") continue

            Map keyMap = getRelationshipExpandedKeyMap(relNode)
            Map targetParameterMap = new HashMap()
            if (valueSource)
                for (Map.Entry keyEntry in keyMap) targetParameterMap.put(keyEntry.value, valueSource.get(keyEntry.key))

            String prettyName = efi.getEntityDefinition(relNode."@related-entity-name").getPrettyName(relNode."@title", internalEntityName)

            infoList.add([type:relNode."@type", title:(relNode."@title"?:""), relatedEntityName:relNode."@related-entity-name",
                    keyMap:keyMap, targetParameterMap:targetParameterMap, prettyName:prettyName])
        }
        return infoList
    }

    EntityDependents getDependentsTree(Deque<String> ancestorEntities) {
        EntityDependents edp = new EntityDependents()
        edp.entityName = internalEntityName
        edp.ed = this

        ancestorEntities.addFirst(this.internalEntityName)

        List<Map> relInfoList = getRelationshipsInfo(null, true)
        for (Map relInfo in relInfoList) {
            edp.allDescendants.add(relInfo.relatedEntityName)
            edp.relationshipInfos.put(relInfo.title+relInfo.relatedEntityName, relInfo)
            EntityDefinition relEd = efi.getEntityDefinition(relInfo.relatedEntityName)
            if (!edp.dependentEntities.containsKey(relEd.internalEntityName) && !ancestorEntities.contains(relEd.internalEntityName)) {
                EntityDependents relEpd = relEd.getDependentsTree(ancestorEntities)
                edp.allDescendants.addAll(relEpd.allDescendants)
                edp.dependentEntities.put(relInfo.relatedEntityName, relEpd)
            }
        }

        ancestorEntities.removeFirst()

        return edp
    }

    static class EntityDependents {
        String entityName
        EntityDefinition ed
        Map<String, EntityDependents> dependentEntities = new HashMap()
        Set<String> allDescendants = new HashSet()
        Map<String, Map> relationshipInfos = new HashMap()
    }

    String getPrettyName(String title, String baseName) {
        StringBuilder prettyName = new StringBuilder()
        for (String part in internalEntityName.split("(?=[A-Z])")) {
            if (baseName && part == baseName) continue
            if (prettyName) prettyName.append(" ")
            prettyName.append(part)
        }
        if (title) {
            boolean addParens = prettyName as boolean
            if (addParens) prettyName.append(" (")
            for (String part in title.split("(?=[A-Z])")) prettyName.append(part).append(" ")
            prettyName.deleteCharAt(prettyName.length()-1)
            if (addParens) prettyName.append(")")
        }
        return prettyName.toString()
    }

    String getColumnName(String fieldName, boolean includeFunctionAndComplex) {
        String cn = columnNameMap.get(fieldName)
        if (cn != null) return cn

        Node fieldNode = this.getFieldNode(fieldName)
        if (!fieldNode) {
            throw new EntityException("Invalid field-name [${fieldName}] for the [${this.getEntityName()}] entity")
        }

        if (isViewEntity()) {
            // NOTE: for view-entity the incoming fieldNode will actually be for an alias element
            StringBuilder colNameBuilder = new StringBuilder()
            if (includeFunctionAndComplex) {
                // TODO: column name view-entity complex-alias (build expression based on complex-alias)
                // TODO: column name for view-entity alias with function (wrap in function, after complex-alias to wrap that too when used)

                // column name for view-entity (prefix with "${entity-alias}.")
                //colName.append(fieldNode."@entity-alias").append('.')
                logger.trace("For view-entity include function and complex not yet supported, for entity [${internalEntityName}], may get bad SQL...")
            }
            // else {
                boolean hasFunction = false
                String function = fieldNode."@function"
                if (function) {
                    hasFunction = true
                    if (function == "count-distinct") colNameBuilder.append("COUNT(DISTINCT ")
                    else colNameBuilder.append(function.toUpperCase()).append('(')
                }
                // column name for view-entity (prefix with "${entity-alias}.")
                colNameBuilder.append(fieldNode."@entity-alias").append('.')

                Node memberEntity = (Node) internalEntityNode."member-entity".find({ it."@entity-alias" == fieldNode."@entity-alias" })
                EntityDefinition memberEd = this.efi.getEntityDefinition(memberEntity."@entity-name")
                String memberFieldName = fieldNode."@field" ? fieldNode."@field" : fieldNode."@name"
                colNameBuilder.append(memberEd.getColumnName(memberFieldName, false))

                if (hasFunction) colNameBuilder.append(')')
            // }
            cn = colNameBuilder.toString()
        } else {
            if (fieldNode."@column-name") {
                cn = fieldNode."@column-name"
            } else {
                cn = camelCaseToUnderscored(fieldNode."@name")
            }
        }

        columnNameMap.put(fieldName, cn)
        return cn
    }

    /** Returns the table name, ie table-name or converted entity-name */
    String getTableName() {
        if (this.internalEntityNode."@table-name") {
            return this.internalEntityNode."@table-name"
        } else {
            return camelCaseToUnderscored(this.internalEntityNode."@entity-name")
        }
    }

    String getFullTableName() {
        if (efi.getDatabaseNode(efi.getEntityGroupName(internalEntityName))?."@use-schemas" != "false") {
            String schemaName = getSchemaName()
            return schemaName ? schemaName + "." + getTableName() : getTableName()
        } else {
            return getTableName()
        }
    }

    String getSchemaName() {
        String schemaName = efi.getDatasourceNode(efi.getEntityGroupName(internalEntityName))?."@schema-name"
        return schemaName ?: null
    }

    boolean isField(String fieldName) { return getFieldNode(fieldName) != null }

    boolean containsPrimaryKey(Map fields) {
        for (String fieldName in this.getPkFieldNames()) if (!fields[fieldName]) return false
        return true
    }

    Map<String, Object> getPrimaryKeys(Map fields) {
        Map<String, Object> pks = new HashMap()
        for (String fieldName in this.getPkFieldNames()) pks.put(fieldName, fields[fieldName])
        return pks
    }

    ListOrderedSet getFieldNames(boolean includePk, boolean includeNonPk) {
        ListOrderedSet nameSet = new ListOrderedSet()
        String nodeName = this.isViewEntity() ? "alias" : "field"
        for (Node node in this.internalEntityNode[nodeName]) {
            if ((includePk && node."@is-pk" == "true") || (includeNonPk && node."@is-pk" != "true")) {
                nameSet.add(node."@name")
            }
        }
        return nameSet
    }
    List<String> getPkFieldNames() {
        if (pkFieldNameList == null) {
            pkFieldNameList = Collections.unmodifiableList(new ArrayList(getFieldNames(true, false).asList()))
        }
        return pkFieldNameList
    }
    List<String> getAllFieldNames() {
        if (allFieldNameList == null) {
            allFieldNameList = Collections.unmodifiableList(new ArrayList(getFieldNames(true, true).asList()))
        }
        return allFieldNameList
    }

    List<Node> getFieldNodes(boolean includePk, boolean includeNonPk) {
        // NOTE: this is not necessarily the fastest way to do this, if it becomes a performance problem replace it with a local Set of field names
        List<Node> nodeList = new ArrayList<Node>()
        String nodeName = this.isViewEntity() ? "alias" : "field"
        for (Node node in this.internalEntityNode[nodeName]) {
            if ((includePk && node."@is-pk" == "true") || (includeNonPk && node."@is-pk" != "true")) {
                nodeList.add(node)
            }
        }
        return nodeList
    }

    void setFields(Map<String, ?> src, Map<String, Object> dest, boolean setIfEmpty, String namePrefix, Boolean pks) {
        if (src == null) return

        for (String fieldName in (pks != null ? this.getFieldNames(pks, !pks) : this.getAllFieldNames())) {
            String sourceFieldName
            if (namePrefix) {
                sourceFieldName = namePrefix + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1)
            } else {
                sourceFieldName = fieldName
            }

            if (src.containsKey(sourceFieldName)) {
                Object value = src.get(sourceFieldName)
                if (value) {
                    if (value instanceof String) {
                        this.setString(fieldName, (String) value, dest)
                    } else {
                        dest.put(fieldName, value)
                    }
                } else if (setIfEmpty) {
                    // treat empty String as null, otherwise set as whatever null or empty type it is
                    if (value != null && value instanceof String) {
                        dest.put(fieldName, null)
                    } else {
                        dest.put(fieldName, value)
                    }
                }
            }
        }
    }

    void setString(String name, String value, Map<String, Object> dest) {
        if (value == null || value == "null") {
            dest.put(name, null)
            return
        }
        if (value == "\null") value == "null"

        Node fieldNode = this.getFieldNode(name)
        if (!fieldNode) dest.put(name, value) // cause an error on purpose

        String javaType = this.efi.getFieldJavaType(fieldNode."@type", internalEntityName)
        switch (EntityFacadeImpl.getJavaTypeInt(javaType)) {
        case 1: dest.put(name, value); break
        case 2: dest.put(name, java.sql.Timestamp.valueOf(value)); break
        case 3: dest.put(name, java.sql.Time.valueOf(value)); break
        case 4: dest.put(name, java.sql.Date.valueOf(value)); break
        case 5: dest.put(name, Integer.valueOf(value)); break
        case 6: dest.put(name, Long.valueOf(value)); break
        case 7: dest.put(name, Float.valueOf(value)); break
        case 8: dest.put(name, Double.valueOf(value)); break
        case 9: dest.put(name, new BigDecimal(value)); break
        case 10: dest.put(name, Boolean.valueOf(value)); break
        case 11: dest.put(name, value); break
        // better way for Blob (12)? probably not...
        case 12: dest.put(name, value); break
        case 13: dest.put(name, value); break
        case 14: dest.put(name, value.asType(java.util.Date.class)); break
        // better way for Collection (15)? maybe parse comma separated, but probably doesn't make sense in the first place
        case 15: dest.put(name, value); break
        }
    }

    protected void expandAliasAlls() {
        if (!isViewEntity()) return
        for (Node aliasAll: this.internalEntityNode."alias-all") {
            Node memberEntity = (Node) this.internalEntityNode."member-entity".find({ it."@entity-alias" == aliasAll."@entity-alias" })
            if (!memberEntity) {
                logger.error("In alias-all with entity-alias [${aliasAll."@entity-alias"}], member-entity with same entity-alias not found, ignoring")
                continue;
            }

            EntityDefinition aliasedEntityDefinition = this.efi.getEntityDefinition(memberEntity."@entity-name")
            if (!aliasedEntityDefinition) {
                logger.error("Entity [${memberEntity."@entity-name"}] referred to in member-entity with entity-alias [${aliasAll."@entity-alias"}] not found, ignoring")
                continue;
            }

            for (Node fieldNode in aliasedEntityDefinition.internalEntityNode.field) {
                // never auto-alias these
                if (fieldNode."@name" == "lastUpdatedStamp") continue
                // if specified as excluded, leave it out
                if (aliasAll."exclude".find({ it."@field" == fieldNode."@name"})) continue

                String aliasName = fieldNode."@name"
                if (aliasAll."@prefix") {
                    StringBuilder newAliasName = new StringBuilder((String) aliasAll."@prefix")
                    newAliasName.append(Character.toUpperCase(aliasName.charAt(0)))
                    newAliasName.append(aliasName.substring(1))
                    aliasName = newAliasName.toString()
                }

                Node existingAliasNode = (Node) this.internalEntityNode.alias.find({ it."@name" == aliasName })
                if (existingAliasNode) {
                    //log differently if this is part of a view-link key-map because that is a common case when a field will be auto-expanded multiple times
                    boolean isInViewLink = false
                    for (Node viewLink in this.internalEntityNode."view-link") {
                        boolean isRel = false
                        if (viewLink."@related-entity-alias" == aliasAll."@entity-alias") {
                            isRel = true
                        } else if (!viewLink."@entity-alias" == aliasAll."@entity-alias") {
                            // not the rel-entity-alias or the entity-alias, so move along
                            continue;
                        }
                        for (Node keyMap in viewLink."key-map") {
                            if (!isRel && keyMap."@field-name" == fieldNode."@name") {
                                isInViewLink = true
                                break
                            } else if (isRel && keyMap."@related-field-name" == fieldNode."@name") {
                                isInViewLink = true
                                break
                            }
                        }
                        if (isInViewLink) break
                    }

                    //already exists, oh well... probably an override, but log just in case
                    String warnMsg = "Throwing out field alias in view entity " + this.internalEntityName +
                            " because one already exists with the alias name [" + aliasName + "] and field name [" +
                            memberEntity."@entity-alias" + "(" + aliasedEntityDefinition.internalEntityName + ")." +
                            fieldNode."@name" + "], existing field name is [" + existingAliasNode."@entity-alias" + "." +
                            existingAliasNode."@field" + "]"
                    if (isInViewLink) {logger.trace(warnMsg)} else {logger.info(warnMsg)}

                    // ship adding the new alias
                    continue
                }

                Node newAlias = this.internalEntityNode.appendNode("alias",
                        [name:aliasName, field:fieldNode."@name",
                        "entity-alias":aliasAll."@entity-alias",
                        "if-from-alias-all":true])
                if (fieldNode.description) newAlias.appendNode(fieldNode."description")
            }
        }
    }

    EntityConditionImplBase makeViewWhereCondition() {
        if (!this.isViewEntity()) return null
        // add the view-entity.entity-condition.econdition(s)
        Node entityCondition = this.internalEntityNode."entity-condition"[0]
        return makeViewListCondition(entityCondition)
    }
    protected EntityConditionImplBase makeViewListCondition(Node conditionsParent) {
        if (conditionsParent == null) return null
        List<EntityConditionImplBase> condList = new ArrayList()
        for (Node dateFilter in conditionsParent."date-filter") {
            // NOTE: this doesn't do context expansion of the valid-date as it doesn't make sense for an entity def to depend on something being in the context
            condList.add((EntityConditionImplBase) this.efi.conditionFactory.makeConditionDate(
                dateFilter."@from-field-name", dateFilter."@thru-field-name", dateFilter."@valid-date" as Timestamp))
        }
        for (Node econdition in conditionsParent."econdition") {
            EntityConditionImplBase cond;
            if (econdition."@value") {
                ConditionField field
                if (econdition."@entity-alias") {
                    Node memberEntity = (Node) this.internalEntityNode."member-entity".find({ it."@entity-alias" == econdition."@entity-alias"})
                    if (!memberEntity) throw new EntityException("The entity-alias [${econdition."@entity-alias"}] was not found in view-entity [${this.internalEntityName}]")
                    EntityDefinition aliasEntityDef = this.efi.getEntityDefinition(memberEntity."@entity-name")
                    field = new ConditionField(econdition."@entity-alias", econdition."@field-name", aliasEntityDef)
                } else {
                    field = new ConditionField(econdition."@field-name")
                }
                // NOTE: may need to convert value from String to object for field
                cond = new FieldValueCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field,
                        EntityConditionFactoryImpl.getComparisonOperator(econdition."@operator"), econdition."@value")
            } else {
                ConditionField field
                if (econdition."@entity-alias") {
                    Node memberEntity = (Node) this.internalEntityNode."member-entity".find({ it."@entity-alias" == econdition."@entity-alias"})
                    if (!memberEntity) throw new EntityException("The entity-alias [${econdition."@entity-alias"}] was not found in view-entity [${this.internalEntityName}]")
                    EntityDefinition aliasEntityDef = this.efi.getEntityDefinition(memberEntity."@entity-name")
                    field = new ConditionField(econdition."@entity-alias", econdition."@field-name", aliasEntityDef)
                } else {
                    field = new ConditionField(econdition."@field-name")
                }
                ConditionField toField
                if (econdition."@to-entity-alias") {
                    Node memberEntity = (Node) this.internalEntityNode."member-entity".find({ it."@entity-alias" == econdition."@to-entity-alias"})
                    if (!memberEntity) throw new EntityException("The entity-alias [${econdition."@to-entity-alias"}] was not found in view-entity [${this.internalEntityName}]")
                    EntityDefinition aliasEntityDef = this.efi.getEntityDefinition(memberEntity."@entity-name")
                    toField = new ConditionField(econdition."@to-entity-alias", econdition."@to-field-name", aliasEntityDef)
                } else {
                    toField = new ConditionField(econdition."@to-field-name")
                }
                cond = new FieldToFieldCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field,
                        EntityConditionFactoryImpl.getComparisonOperator(econdition."@operator"), toField)
            }
            if (cond && econdition."@ignore-case" == "true") cond.ignoreCase()
            if (cond) condList.add(cond)
        }
        for (Node econditions in conditionsParent."econditions") {
            EntityConditionImplBase cond = this.makeViewListCondition(econditions)
            if (cond) condList.add(cond)
        }
        //logger.info("TOREMOVE In makeViewListCondition for entity [${entityName}] resulting condList: ${condList}")
        if (!condList) return null
        if (condList.size() == 1) return condList.get(0)
        JoinOperator op = (conditionsParent."@combine" == "or" ? JoinOperator.OR : JoinOperator.AND)
        return (EntityConditionImplBase) this.efi.conditionFactory.makeCondition((List<EntityCondition>) condList, op)
    }

    EntityConditionImplBase makeViewHavingCondition() {
        if (!this.isViewEntity()) return null
        // add the view-entity.entity-condition.having-econditions
        Node havingEconditions = (Node) this.internalEntityNode."entity-condition"?.getAt(0)?."having-econditions"?.getAt(0)
        if (!havingEconditions) return null
        return makeViewListCondition(havingEconditions)
    }

    @Override
    int hashCode() {
        return this.internalEntityName.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        EntityDefinition that = (EntityDefinition) o
        if (!this.internalEntityName.equals(that.internalEntityName)) return false
        return true
    }

    protected static Map<String, String> camelToUnderscoreMap = new HashMap()
    static String camelCaseToUnderscored(String camelCase) {
        if (!camelCase) return ""
        String usv = camelToUnderscoreMap.get(camelCase)
        if (usv) return usv

        StringBuilder underscored = new StringBuilder()
        underscored.append(Character.toUpperCase(camelCase.charAt(0)))
        int inPos = 1
        while (inPos < camelCase.length()) {
            char curChar = camelCase.charAt(inPos)
            if (Character.isUpperCase(curChar)) underscored.append('_')
            underscored.append(Character.toUpperCase(curChar))
            inPos++
        }

        usv = underscored.toString()
        camelToUnderscoreMap.put(camelCase, usv)
        return usv
    }
}
