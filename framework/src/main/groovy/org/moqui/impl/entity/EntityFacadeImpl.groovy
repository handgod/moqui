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

import net.sf.ehcache.Ehcache
import org.apache.commons.collections.set.ListOrderedSet

import org.moqui.context.Cache
import org.moqui.context.ResourceReference
import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.CacheImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.w3c.dom.Element

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import javax.sql.XADataSource

import org.moqui.entity.*
import org.moqui.BaseException

import java.sql.Timestamp

class EntityFacadeImpl implements EntityFacade {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EntityFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi
    protected final String tenantId

    protected final EntityConditionFactoryImpl entityConditionFactory

    protected final Map<String, EntityDatasourceFactory> datasourceFactoryByGroupMap = new HashMap()

    /** Cache with entity name as the key and an EntityDefinition as the value; clear this cache to reload entity def */
    protected final Cache entityDefinitionCache
    /** Cache with entity name as the key and List of file location Strings as the value, Map<String, List<String>> */
    protected final Cache entityLocationCache
    /** Sequence name (often entity name) is the key and the value is an array of 2 Longs the first is the next
     * available value and the second is the highest value reserved/cached in the bank. */
    protected final Cache entitySequenceBankCache

    protected final Map<String, List<EntityEcaRule>> eecaRulesByEntityName = new HashMap()
    protected final Map<String, String> entityGroupNameMap = new HashMap()

    // this will be used to temporarily cache root Node objects of entity XML files, used when loading a bunch at once,
    //     should be null otherwise to prevent its use
    protected Map<String, Node> tempEntityFileNodeMap = null

    protected EntityDbMeta dbMeta = null

    EntityFacadeImpl(ExecutionContextFactoryImpl ecfi, String tenantId) {
        this.ecfi = ecfi
        this.tenantId = tenantId ?: "DEFAULT"
        this.entityConditionFactory = new EntityConditionFactoryImpl(this)

        // init connection pool (DataSource) for each group
        this.initAllDatasources()

        // init entity meta-data
        this.entityDefinitionCache = ecfi.getCacheFacade().getCache("entity.definition")
        this.entityLocationCache = ecfi.getCacheFacade().getCache("entity.location")
        // NOTE: don't try to load entity locations before constructor is complete; this.loadAllEntityLocations()
        this.entitySequenceBankCache = ecfi.getCacheFacade().getCache("entity.sequence.bank")

        // EECA rule tables
        loadEecaRulesAll()
    }

    void checkInitDatasourceTables() {
        // if startup-add-missing=true check tables now
        for (Node datasourceNode in ecfi.getConfXmlRoot()."entity-facade"[0]."datasource") {
            if (datasourceNode."@startup-add-missing" == "true") {
                loadAllEntityLocations()
                String groupName = datasourceNode."@group-name"
                logger.info("Checking all tables in group [${groupName}]")
                long currentTime = System.currentTimeMillis()
                checkAllEntityTables(groupName)
                logger.info("Checked all tables in group [${groupName}] in ${(System.currentTimeMillis() - currentTime)/1000} seconds")
            }
        }
    }

    protected void initAllDatasources() {
        for (Node datasourceNode in this.ecfi.getConfXmlRoot()."entity-facade"[0]."datasource") {
            String groupName = datasourceNode."@group-name"
            String objectFactoryClass = datasourceNode."@object-factory" ?: "org.moqui.impl.entity.EntityDatasourceFactoryImpl"
            EntityDatasourceFactory edf = (EntityDatasourceFactory) Thread.currentThread().getContextClassLoader().loadClass(objectFactoryClass).newInstance()
            datasourceFactoryByGroupMap.put(groupName, edf.init(this, datasourceNode, this.tenantId))
        }
    }

    Set<String> getDatasourceGroupNames() {
        Set<String> groupNames = new TreeSet<String>()
        for (Node datasourceNode in this.ecfi.getConfXmlRoot()."entity-facade"[0]."datasource")
            groupNames.add(datasourceNode."@group-name")
        return groupNames
    }

    ExecutionContextFactoryImpl getEcfi() { return ecfi }

    static int getTxIsolationFromString(String isolationLevel) {
        if (!isolationLevel) return -1
        if ("Serializable".equals(isolationLevel)) {
            return Connection.TRANSACTION_SERIALIZABLE
        } else if ("RepeatableRead".equals(isolationLevel)) {
            return Connection.TRANSACTION_REPEATABLE_READ
        } else if ("ReadUncommitted".equals(isolationLevel)) {
            return Connection.TRANSACTION_READ_UNCOMMITTED
        } else if ("ReadCommitted".equals(isolationLevel)) {
            return Connection.TRANSACTION_READ_COMMITTED
        } else if ("None".equals(isolationLevel)) {
            return Connection.TRANSACTION_NONE
        } else {
            return -1
        }
    }

    List<ResourceReference> getAllEntityFileLocations() {
        List<ResourceReference> entityRrList = new LinkedList()

        // loop through all of the entity-facade.load-entity nodes, check each for "<entities>" root element
        for (Node loadEntity in this.ecfi.getConfXmlRoot()."entity-facade"[0]."load-entity") {
            entityRrList.add(this.ecfi.resourceFacade.getLocationReference(loadEntity."@location"))
        }

        // loop through components look for XML files in the entity directory, check each for "<entities>" root element
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference entityDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/entity")
            if (entityDirRr.supportsAll()) {
                // if directory doesn't exist skip it, component doesn't have an entity directory
                if (!entityDirRr.exists || !entityDirRr.isDirectory()) continue
                // get all files in the directory
                TreeMap<String, ResourceReference> entityDirEntries = new TreeMap<String, ResourceReference>()
                for (ResourceReference entityRr in entityDirRr.directoryEntries) {
                    if (!entityRr.isFile() || !entityRr.location.endsWith(".xml")) continue
                    entityDirEntries.put(entityRr.getFileName(), entityRr)
                }
                for (Map.Entry<String, ResourceReference> entityDirEntry in entityDirEntries) {
                    entityRrList.add(entityDirEntry.getValue())
                }
            } else {
                // just warn here, no exception because any non-file component location would blow everything up
                logger.warn("Cannot load entity directory in component location [${location}] because protocol [${entityDirRr.uri.scheme}] is not supported.")
            }
        }

        return entityRrList
    }

    protected synchronized void loadAllEntityLocations() {
        // load all entity files based on ResourceReference
        for (ResourceReference entityRr in getAllEntityFileLocations()) this.loadEntityFileLocations(entityRr)

        // look for view-entity definitions in the database (moqui.entity.view.DbViewEntity)
        if (entityLocationCache.get("moqui.entity.view.DbViewEntity")) {
            int numDbViewEntities = 0
            for (EntityValue dbViewEntity in makeFind("moqui.entity.view.DbViewEntity").list()) {
                if (dbViewEntity.packageName) {
                    List pkgList = (List) this.entityLocationCache.get(dbViewEntity.packageName + "." + dbViewEntity.dbViewEntityName)
                    if (!pkgList) {
                        pkgList = new LinkedList()
                        this.entityLocationCache.put(dbViewEntity.packageName + "." + dbViewEntity.dbViewEntityName, pkgList)
                    }
                    if (!pkgList.contains("_DB_VIEW_ENTITY_")) pkgList.add("_DB_VIEW_ENTITY_")
                }

                List nameList = (List) this.entityLocationCache.get((String) dbViewEntity.dbViewEntityName)
                if (!nameList) {
                    nameList = new LinkedList()
                    // put in cache under both plain entityName and fullEntityName
                    this.entityLocationCache.put((String) dbViewEntity.dbViewEntityName, nameList)
                }
                if (!nameList.contains("_DB_VIEW_ENTITY_")) nameList.add("_DB_VIEW_ENTITY_")

                numDbViewEntities++
            }
            if (logger.infoEnabled) logger.info("Found [${numDbViewEntities}] view-entity definitions in database (moqui.entity.view.DbViewEntity)")
        } else {
            logger.warn("Could not find view-entity definitions in database (moqui.entity.view.DbViewEntity), no location found for the moqui.entity.view.DbViewEntity entity.")
        }

        /* a little code to show all entities and their locations
        Set<String> enSet = new TreeSet(entityLocationCache.keySet())
        for (String en in enSet) {
            List lst = entityLocationCache.get(en)
            entityLocationCache.put(en, Collections.unmodifiableList(lst))
            logger.warn("TOREMOVE entity ${en}: ${lst}")
        }
        */
    }

    protected synchronized void loadEntityFileLocations(ResourceReference entityRr) {
        InputStream entityStream = entityRr.openStream()
        if (entityStream == null) throw new BaseException("Could not open stream to entity file at [${entityRr.location}]")
        Node entityRoot = new XmlParser().parse(entityStream)
        entityStream.close()
        if (entityRoot.name() == "entities") {
            // loop through all entity, view-entity, and extend-entity and add file location to List for any entity named
            int numEntities = 0
            for (Node entity in entityRoot.children()) {
                String entityName = (String) entity."@entity-name"
                String packageName = (String) entity."@package-name"
                if (packageName) {
                    List pkgList = (List) this.entityLocationCache.get(packageName + "." + entityName)
                    if (!pkgList) {
                        pkgList = new LinkedList()
                        this.entityLocationCache.put(packageName + "." + entityName, pkgList)
                    }
                    if (!pkgList.contains(entityRr.location)) pkgList.add(entityRr.location)
                }

                List nameList = (List) this.entityLocationCache.get(entityName)
                if (!nameList) {
                    nameList = new LinkedList()
                    // put in cache under both plain entityName and fullEntityName
                    this.entityLocationCache.put(entityName, nameList)
                }
                if (!nameList.contains(entityRr.location)) nameList.add(entityRr.location)

                numEntities++
            }
            logger.info("Found [${numEntities}] entity definitions in [${entityRr.location}]")
        }
    }

    protected EntityDefinition loadEntityDefinition(String entityName) {
        EntityDefinition ed = (EntityDefinition) entityDefinitionCache.get(entityName)
        if (ed) return ed

        List entityLocationList = (List) entityLocationCache.get(entityName)
        if (!entityLocationList) {
            if (logger.warnEnabled) logger.warn("No location cache found for entity-name [${entityName}], reloading ALL entity file locations known.")
            this.loadAllEntityLocations()
            entityLocationList = (List) entityLocationCache.get(entityName)
            // no locations found for this entity, entity probably doesn't exist
            if (!entityLocationList) {
                throw new EntityException("No definition found for entity-name [${entityName}]")
            }
        }

        String packageName = null
        if (entityName.contains('.')) {
            packageName = entityName.substring(0, entityName.lastIndexOf("."))
            entityName = entityName.substring(entityName.lastIndexOf(".")+1)
        }

        // if (!packageName) logger.warn("TOREMOVE finding entity def for [${entityName}] with no packageName, entityLocationList=${entityLocationList}")

        // If this is a moqui.entity.view.DbViewEntity, handle that in a special way (generate the Nodes from the DB records)
        if (entityLocationList.contains("_DB_VIEW_ENTITY_")) {
            EntityValue dbViewEntity = makeFind("moqui.entity.view.DbViewEntity").condition("dbViewEntityName", entityName).one()
            if (dbViewEntity == null) throw new EntityException("Could not find DbViewEntity with name ${entityName}")
            Node dbViewNode = new Node(null, "view-entity", ["entity-name":entityName, "package-name":dbViewEntity.packageName])
            if (dbViewEntity.cache == "Y") dbViewNode.attributes().put("cache", "true")
            else if (dbViewEntity.cache == "N") dbViewNode.attributes().put("cache", "false")

            EntityList memberList = makeFind("moqui.entity.view.DbViewEntityMember").condition("dbViewEntityName", entityName).list()
            for (EntityValue dbViewEntityMember in memberList) {
                Node memberEntity = dbViewNode.appendNode("member-entity",
                        ["entity-alias":dbViewEntityMember.entityAlias, "entity-name":dbViewEntityMember.entityName])
                if (dbViewEntityMember.joinFromAlias) {
                    memberEntity.attributes().put("join-from-alias", dbViewEntityMember.joinFromAlias)
                    if (dbViewEntityMember.joinOptional == "Y") memberEntity.attributes().put("join-optional", "true")
                }

                EntityList dbViewEntityKeyMapList = makeFind("moqui.entity.view.DbViewEntityKeyMap")
                        .condition(["dbViewEntityName":entityName, "joinFromAlias":dbViewEntityMember.joinFromAlias,
                            "entityAlias":dbViewEntityMember.entityAlias])
                        .list()
                for (EntityValue dbViewEntityKeyMap in dbViewEntityKeyMapList) {
                    Node keyMapNode = memberEntity.appendNode("key-map", ["field-name":dbViewEntityKeyMap.fieldName])
                    if (dbViewEntityKeyMap.relatedFieldName)
                        keyMapNode.attributes().put("related-field-name", dbViewEntityKeyMap.relatedFieldName)
                }
            }
            for (EntityValue dbViewEntityAlias in makeFind("moqui.entity.view.DbViewEntityAlias").condition("dbViewEntityName", entityName).list()) {
                Node aliasNode = dbViewNode.appendNode("alias",
                        ["name":dbViewEntityAlias.fieldAlias, "entity-alias":dbViewEntityAlias.entityAlias])
                if (dbViewEntityAlias.fieldName) aliasNode.attributes().put("field", dbViewEntityAlias.fieldName)
                if (dbViewEntityAlias.functionName) aliasNode.attributes().put("function", dbViewEntityAlias.functionName)
            }

            // create the new EntityDefinition
            ed = new EntityDefinition(this, dbViewNode)
            // cache it under both entityName and fullEntityName
            entityDefinitionCache.put(ed.getEntityName(), ed)
            entityDefinitionCache.put(ed.getFullEntityName(), ed)
            // send it on its way
            return ed
        }

        // get entity, view-entity and extend-entity Nodes for entity from each location
        Node entityNode = null
        List<Node> extendEntityNodes = new ArrayList<Node>()
        for (String location in entityLocationList) {
            Node entityRoot = null
            if (tempEntityFileNodeMap != null) entityRoot = tempEntityFileNodeMap.get(location)
            if (entityRoot == null) {
                InputStream entityStream = this.ecfi.resourceFacade.getLocationStream(location)
                entityRoot = new XmlParser().parse(entityStream)
                entityStream.close()
                if (tempEntityFileNodeMap != null) tempEntityFileNodeMap.put(location, entityRoot)
            }
            // filter by package-name if specified, otherwise grab whatever
            for (Node childNode in entityRoot.children().findAll({ it."@entity-name" == entityName && (packageName ? it."@package-name" == packageName : true) })) {
                if (childNode.name() == "extend-entity") {
                    extendEntityNodes.add(childNode)
                } else {
                    if (entityNode != null) logger.warn("Entity [${entityName}] was found again, so overriding")
                    entityNode = childNode
                }
            }
        }

        if (!entityNode) throw new EntityException("No definition found for entity [${entityName}]${packageName ? ' in package ['+packageName+']' : ''}")

        // merge the extend-entity nodes
        for (Node extendEntity in extendEntityNodes) {
            // if package-name attributes don't match, skip
            if (entityNode."@package-name" != extendEntity."@package-name") continue
            // merge attributes
            entityNode.attributes().putAll(extendEntity.attributes())
            // merge field nodes
            for (Node childOverrideNode in extendEntity."field") {
                String keyValue = childOverrideNode."@name"
                Node childBaseNode = (Node) entityNode."field".find({ it."@name" == keyValue })
                if (childBaseNode) childBaseNode.attributes().putAll(childOverrideNode.attributes())
                else entityNode.append(childOverrideNode)
            }
            // add relationship, key-map (copy over, will get child nodes too
            for (Node copyNode in extendEntity."relationship") entityNode.append(copyNode)
            // add index, index-field
            for (Node copyNode in extendEntity."index") entityNode.append(copyNode)
        }

        // create the new EntityDefinition
        ed = new EntityDefinition(this, entityNode)
        // cache it
        entityDefinitionCache.put(ed.getEntityName(), ed)
        entityDefinitionCache.put(ed.getFullEntityName(), ed)
        // send it on its way
        return ed
    }

    /** This method is called only when the tools need all automatic reverse-many relationships.
     *
     * During normal operation reverse-many relationships are only needed when explicitly referenced, and these are
     * handled in the EntityDefinition.getRelationshipNode() method one at a time (only those used are loaded).
     */
    synchronized void createAllAutoReverseManyRelationships() {
        int relationshipsCreated = 0
        Set<String> entityNameSet = getAllEntityNames()
        for (String entityName in entityNameSet) {
            EntityDefinition ed = getEntityDefinition(entityName)
            List<String> pkSet = ed.getPkFieldNames()
            for (Node relNode in ed.entityNode."relationship") {
                if (relNode."@type" == "many") continue

                EntityDefinition reverseEd
                try {
                    reverseEd = getEntityDefinition(relNode."@related-entity-name")
                } catch (EntityException e) {
                    logger.warn("Error getting definition for entity [${relNode."@related-entity-name"}] referred to in a relationship of entity [${entityName}]: ${e.toString()}")
                    continue
                }
                List<String> reversePkSet = reverseEd.getPkFieldNames()
                String relType = reversePkSet.equals(pkSet) ? "one-nofk" : "many"

                // does a many relationship coming back already exist?
                Node reverseRelNode = (Node) reverseEd.entityNode."relationship".find(
                        { it."@related-entity-name" == ed.entityName && it."@type" == relType })
                if (reverseRelNode != null) {
                    // make sure has is-one-reverse="true"
                    reverseRelNode.attributes().put("is-one-reverse", "true")
                    continue
                }

                // track the fact that the related entity has others pointing back to it
                if (!ed.isViewEntity()) reverseEd.entityNode.attributes().put("has-dependents", "true")

                // create a new reverse-many relationship
                Map keyMap = ed.getRelationshipExpandedKeyMap(relNode)

                Node newRelNode = reverseEd.entityNode.appendNode("relationship",
                        ["related-entity-name":ed.entityName, "type":relType, "is-one-reverse":"true"])
                if (relNode."@title") newRelNode.attributes().title = relNode."@title"
                for (Map.Entry keyEntry in keyMap) {
                    // add a key-map with the reverse fields
                    newRelNode.appendNode("key-map", ["field-name":keyEntry.value, "related-field-name":keyEntry.key])
                }
                relationshipsCreated++
            }
        }

        if (logger.infoEnabled && relationshipsCreated > 0) logger.info("Created ${relationshipsCreated} automatic reverse-many relationships")
    }

    void loadEecaRulesAll() {
        if (eecaRulesByEntityName.size() > 0) eecaRulesByEntityName.clear()

        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference entityDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/entity")
            if (entityDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!entityDirRr.isDirectory()) continue
                for (ResourceReference rr in entityDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".eecas.xml")) continue
                    loadEecaRulesFile(rr)
                }
            } else {
                logger.warn("Can't load EECA rules from component at [${entityDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
    }
    void loadEecaRulesFile(ResourceReference rr) {
        InputStream is = null
        try {
            is = rr.openStream()
            Node serviceRoot = new XmlParser().parse(is)
            int numLoaded = 0
            for (Node secaNode in serviceRoot."eeca") {
                EntityEcaRule ser = new EntityEcaRule(ecfi, secaNode, rr.location)
                String entityName = ser.entityName
                // remove the hash if there is one to more consistently match the service name
                if (entityName.contains("#")) entityName = entityName.replace("#", "")
                List<EntityEcaRule> lst = eecaRulesByEntityName.get(entityName)
                if (!lst) {
                    lst = new LinkedList()
                    eecaRulesByEntityName.put(entityName, lst)
                }
                lst.add(ser)
                numLoaded++
            }
            if (logger.infoEnabled) logger.info("Loaded [${numLoaded}] Entity ECA rules from [${rr.location}]")
        } catch (IOException e) {
            // probably because there is no resource at that location, so do nothing
            if (logger.traceEnabled) logger.trace("Error loading EECA rules from [${rr.location}]", e)
        } finally {
            if (is != null) is.close()
        }
    }

    void runEecaRules(String entityName, Map fieldValues, String operation, boolean before) {
        // if Entity ECA rules disabled in ArtifactExecutionFacade, just return immediately
        if (((ArtifactExecutionFacadeImpl) this.ecfi.getEci().getArtifactExecution()).entityEcaDisabled()) return

        List<EntityEcaRule> lst = eecaRulesByEntityName.get(entityName)
        for (EntityEcaRule eer in lst) {
            eer.runIfMatches(entityName, fieldValues, operation, before, ecfi.executionContext)
        }
    }

    void destroy() {
        Set<String> groupNames = this.datasourceFactoryByGroupMap.keySet()
        for (String groupName in groupNames) {
            EntityDatasourceFactory edf = this.datasourceFactoryByGroupMap.get(groupName)
            this.datasourceFactoryByGroupMap.put(groupName, null)
            edf.destroy()
        }
    }

    void checkAllEntityTables(String groupName) {
        // TODO: load framework entities first, then component/mantle/etc entities for better FKs on first pass
        for (String entityName in getAllEntityNames()) {
            EntityDefinition ed = getEntityDefinition(entityName)
            String entityGroupName = getEntityGroupName(ed)
            if (!groupName || groupName == entityGroupName) getEntityDbMeta().checkTableRuntime(ed)
        }
    }

    /** This uses the data from the loadAllEntityLocations() method, so that must be called first (it is called in the
     * constructor, and the cache must not have been cleared since. */
    Set<String> getAllEntityNames() {
        TreeSet<String> allNames = new TreeSet()
        // only add full entity names (with package-name in it, will always have at least one dot)
        for (String en in entityLocationCache.keySet()) if (en.contains(".")) allNames.add(en)
        return allNames
    }

    EntityDefinition getEntityDefinition(String entityName) {
        if (!entityName) return null
        EntityDefinition ed = (EntityDefinition) this.entityDefinitionCache.get(entityName)
        if (ed != null) return ed
        return loadEntityDefinition(entityName)
    }

    void clearEntityDefinitionFromCache(String entityName) {
        EntityDefinition ed = (EntityDefinition) this.entityDefinitionCache.get(entityName)
        if (ed != null) {
            this.entityDefinitionCache.remove(ed.getEntityName())
            this.entityDefinitionCache.remove(ed.getFullEntityName())
        }
    }

    List<Map<String, Object>> getAllEntitiesInfo(String orderByField, boolean masterEntitiesOnly) {
        if (masterEntitiesOnly) createAllAutoReverseManyRelationships()

        tempEntityFileNodeMap = new HashMap()

        List<Map<String, Object>> eil = new LinkedList()
        for (String en in getAllEntityNames()) {
            EntityDefinition ed = null
            try { ed = getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (ed == null) continue

            if (masterEntitiesOnly) {
                if (!(ed.entityNode."@has-dependents" == "true") || en.endsWith("Type") ||
                        en == "moqui.basic.Enumeration" || en == "moqui.basic.StatusItem") continue
                if (ed.getPkFieldNames().size() > 1) continue
            }

            eil.add((Map<String, Object>) [entityName:ed.entityName, "package":ed.entityNode."@package-name",
                    isView:(ed.isViewEntity() ? "true" : "false"), fullEntityName:ed.fullEntityName])
        }

        tempEntityFileNodeMap = null

        if (orderByField) StupidUtilities.orderMapList((List<Map>) eil, [orderByField])
        return eil
    }

    List<Map<String, Object>> getAllEntityRelatedFields(String en, String orderByField, String dbViewEntityName) {
        // make sure reverse-one many relationships exist
        createAllAutoReverseManyRelationships()

        EntityValue dbViewEntity = dbViewEntityName ? makeFind("moqui.entity.view.DbViewEntity").condition("dbViewEntityName", dbViewEntityName).one() : null

        List<Map<String, Object>> efl = new LinkedList()
        EntityDefinition ed = null
        try { ed = getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
        if (ed == null) return efl

        // first get fields of the main entity
        for (String fn in ed.getAllFieldNames()) {
            Node fieldNode = ed.getFieldNode(fn)

            boolean inDbView = false
            String functionName = null
            EntityValue aliasVal = makeFind("moqui.entity.view.DbViewEntityAlias")
                .condition([dbViewEntityName:dbViewEntityName, entityAlias:"MASTER", fieldName:fn]).one()
            if (aliasVal) {
                inDbView = true
                functionName = aliasVal.functionName
            }

            efl.add((Map<String, Object>) [entityName:en, fieldName:fn, type:fieldNode."@type", cardinality:"one",
                    inDbView:inDbView, functionName:functionName])
        }

        // loop through all related entities and get their fields too
        for (Map relInfo in ed.getRelationshipsInfo(null, false)) {
            //[type:relNode."@type", title:(relNode."@title"?:""), relatedEntityName:relNode."@related-entity-name",
            //        keyMap:keyMap, targetParameterMap:targetParameterMap, prettyName:prettyName]
            EntityDefinition red = null
            try { red = getEntityDefinition(relInfo.relatedEntityName) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (red == null) continue

            EntityValue dbViewEntityMember = null
            if (dbViewEntity) dbViewEntityMember = makeFind("moqui.entity.view.DbViewEntityMember")
                    .condition([dbViewEntityName:dbViewEntityName, entityName:red.getFullEntityName()]).one()

            for (String fn in red.getAllFieldNames()) {
                Node fieldNode = red.getFieldNode(fn)
                boolean inDbView = false
                String functionName = null
                if (dbViewEntityMember) {
                    EntityValue aliasVal = makeFind("moqui.entity.view.DbViewEntityAlias")
                        .condition([dbViewEntityName:dbViewEntityName, entityAlias:dbViewEntityMember.entityAlias, fieldName:fn]).one()
                    if (aliasVal) {
                        inDbView = true
                        functionName = aliasVal.functionName
                    }
                }
                efl.add((Map<String, Object>) [entityName:relInfo.relatedEntityName, fieldName:fn, type:fieldNode."@type",
                        cardinality:relInfo.type, title:relInfo.title, inDbView:inDbView, functionName:functionName])
            }
        }

        if (orderByField) StupidUtilities.orderMapList((List<Map>) efl, [orderByField])
        return efl
    }

    CacheImpl getCacheOne(String entityName) { return ecfi.getCacheFacade().getCacheImpl("entity.${tenantId}.one.${entityName}") }
    CacheImpl getCacheOneRa(String entityName) { return ecfi.getCacheFacade().getCacheImpl("entity.${tenantId}.one_ra.${entityName}") }
    CacheImpl getCacheOneBf() { return ecfi.getCacheFacade().getCacheImpl("entity.${tenantId}.one_bf") }
    CacheImpl getCacheList(String entityName) { return ecfi.getCacheFacade().getCacheImpl("entity.${tenantId}.list.${entityName}") }
    CacheImpl getCacheListRa(String entityName) { return ecfi.getCacheFacade().getCacheImpl("entity.${tenantId}.list_ra.${entityName}") }
    CacheImpl getCacheCount(String entityName) { return ecfi.getCacheFacade().getCacheImpl("entity.${tenantId}.count.${entityName}") }

    void clearCacheForValue(EntityValueBase evb, boolean isCreate) {
        try {
            EntityDefinition ed = evb.getEntityDefinition()
            if (ed.getEntityNode()."@use-cache" == "never") return
            String entityName = ed.getEntityName()
            EntityCondition pkCondition = getConditionFactory().makeCondition(evb.getPrimaryKeys())

            // clear one cache
            if (ecfi.getCacheFacade().cacheExists("entity.${tenantId}.one.${entityName}")) {
                Cache entityOneCache = getCacheOne(entityName)
                Ehcache eocEhc = entityOneCache.getInternalCache()
                // clear by PK, most common scenario
                eocEhc.remove(pkCondition)

                // NOTE: these two have to be done whether or not it is a create because of non-pk updates, etc
                // see if there are any one RA entries
                Cache oneRaCache = getCacheOneRa(entityName)
                if (oneRaCache.containsKey(pkCondition)) {
                    List raKeyList = (List) oneRaCache.get(pkCondition)
                    for (EntityCondition ec in raKeyList) {
                        eocEhc.remove(ec)
                    }
                    // we've cleared all entries that this was referring to, so clean it out too
                    oneRaCache.remove(pkCondition)
                }
                // see if there are any cached entries with no result using the bf (brute-force) matching
                Cache oneBfCache = getCacheOneBf()
                Set bfKeySet = (Set) oneBfCache.get(entityName)
                if (bfKeySet) {
                    Set keysToRemove = new HashSet()
                    for (EntityCondition bfKey in bfKeySet) {
                        if (bfKey.mapMatches(evb)) {
                            eocEhc.remove(bfKey)
                            keysToRemove.add(bfKey)
                        }
                    }
                    for (EntityCondition key in keysToRemove) bfKeySet.remove(key)
                }
            }

            // clear list cache, use reverse-associative Map (also a Cache)
            if (ecfi.getCacheFacade().cacheExists("entity.${tenantId}.list_ra.${entityName}")) {
                // if this was a create the RA cache won't help, so go through EACH entry and see if it matches the created value
                if (isCreate) {
                    CacheImpl entityListCache = getCacheList(entityName)
                    Ehcache elEhc = entityListCache.getInternalCache()
                    for (EntityCondition ec in elEhc.getKeys()) {
                        // any way to efficiently clear out the RA cache for these? for now just leave and they are handled eventually
                        if (ec.mapMatches(evb)) elEhc.remove(ec)
                    }
                } else {
                    Cache listRaCache = getCacheListRa(entityName)
                    if (listRaCache.containsKey(pkCondition)) {
                        List raKeyList = (List) listRaCache.get(pkCondition)
                        // logger.warn("TOREMOVE for entity ${entityName}, for pkCondition [${pkCondition}], raKeyList for clear=${raKeyList}")
                        CacheImpl entityListCache = getCacheList(entityName)
                        Ehcache elcEhc = entityListCache.getInternalCache()
                        for (Object raKey in raKeyList) {
                            if (raKey instanceof EntityCondition) {
                                EntityCondition ec = (EntityCondition) raKey
                                // this may have already been cleared, but it is a waste of time to check for that explicitly
                                elcEhc.remove(ec)
                            } else {
                                Map viewEcMap = (Map) raKey
                                CacheImpl viewEntityListCache = getCacheList(viewEcMap.ven)
                                Ehcache velcEhc = viewEntityListCache.getInternalCache()
                                // this may have already been cleared, but it is a waste of time to check for that explicitly
                                velcEhc.remove(viewEcMap.ec)
                            }
                        }
                        // we've cleared all entries that this was referring to, so clean it out too
                        listRaCache.remove(pkCondition)
                    }
                }
            }

            // clear count cache (no RA because we only have a count to work with, just match by condition)
            if (ecfi.getCacheFacade().cacheExists("entity.${tenantId}.count.${entityName}")) {
                CacheImpl entityCountCache = getCacheCount(entityName)
                Ehcache elEhc = entityCountCache.getInternalCache()
                for (EntityCondition ec in elEhc.getKeys()) {
                    if (ec.mapMatches(evb)) elEhc.remove(ec)
                }
            }
        } catch (Throwable t) {
            logger.error("Suppressed error in entity cache clearing [${evb.getEntityName()}; ${isCreate ? 'create' : 'non-create'}]", t)
        }
    }
    void registerCacheOneRa(String entityName, EntityCondition ec, EntityValueBase evb) {
        // don't skip it for null values because we're caching those too: if (evb == null) return
        if (evb == null) {
            // can't use RA cache because we don't know the PK, so use a brute-force cache but keep it separate to perform better
            Cache oneBfCache = getCacheOneBf()
            Set bfKeySet = (Set) oneBfCache.get(entityName)
            if (bfKeySet == null) {
                bfKeySet = new HashSet()
                oneBfCache.put(entityName, bfKeySet)
            }
            bfKeySet.add(ec)
        } else {
            Cache oneRaCache = getCacheOneRa(entityName)
            EntityCondition pkCondition = getConditionFactory().makeCondition(evb.getPrimaryKeys())
            // if the condition matches the primary key, no need for an RA entry
            if (pkCondition == ec) return
            List raKeyList = (List) oneRaCache.get(pkCondition)
            if (raKeyList == null) {
                raKeyList = new ArrayList()
                oneRaCache.put(pkCondition, raKeyList)
            }
            raKeyList.add(ec)
        }
    }
    void registerCacheListRa(String entityName, EntityCondition ec, EntityListImpl eli) {
        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed.isViewEntity()) {
            // go through each member-entity
            for (Node memberEntityNode in ed.getEntityNode()."member-entity") {
                Map mePkFieldToAliasNameMap = ed.getMePkFieldToAliasNameMap(memberEntityNode."@entity-alias")

                // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, got PK field to alias map: ${mePkFieldToAliasNameMap}")

                // create EntityCondition with pk fields
                // store with main ec with view-entity name in a RA cache for view entities for the member-entity name
                // with cache key of member-entity PK EntityCondition obj
                Cache listViewRaCache = getCacheListRa(memberEntityNode.'@entity-name')
                for (EntityValue ev in eli) {
                    Map pkCondMap = new HashMap()
                    for (Map.Entry mePkEntry in mePkFieldToAliasNameMap) pkCondMap.put(mePkEntry.getKey(), ev.get(mePkEntry.getValue()))
                    EntityCondition pkCondition = getConditionFactory().makeCondition(pkCondMap)
                    List raKeyList = (List) listViewRaCache.get(pkCondition)
                    if (!raKeyList) {
                        raKeyList = new ArrayList()
                        listViewRaCache.put(pkCondition, raKeyList)
                    }
                    raKeyList.add([ven:entityName, ec:ec])
                    // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, for pkCondition [${pkCondition}], raKeyList after add=${raKeyList}")
                }
            }
        } else {
            Cache listRaCache = getCacheListRa(entityName)
            for (EntityValue ev in eli) {
                EntityCondition pkCondition = getConditionFactory().makeCondition(ev.getPrimaryKeys())
                List raKeyList = (List) listRaCache.get(pkCondition)
                if (!raKeyList) {
                    raKeyList = new ArrayList()
                    listRaCache.put(pkCondition, raKeyList)
                }
                raKeyList.add(ec)
            }
        }
    }

    Node getDatabaseNode(String groupName) {
        String databaseConfName = getDatabaseConfName(groupName)
        return (Node) ecfi.confXmlRoot."database-list"[0].database.find({ it."@name" == databaseConfName })
    }
    String getDatabaseConfName(String groupName) {
        Node datasourceNode = (Node) ecfi.confXmlRoot."entity-facade"[0].datasource.find({ it."@group-name" == groupName })
        return datasourceNode."@database-conf-name"
    }

    Node getDatasourceNode(String groupName) {
        return (Node) ecfi.confXmlRoot."entity-facade"[0].datasource.find({ it."@group-name" == groupName })
    }

    EntityDbMeta getEntityDbMeta() { return dbMeta ? dbMeta : (dbMeta = new EntityDbMeta(this)) }

    // ========== Interface Implementations ==========

    @Override
    EntityConditionFactory getConditionFactory() { return this.entityConditionFactory }

    @Override
    EntityValue makeValue(String entityName) {
        if (!entityName) throw new EntityException("No entityName passed to EntityFacade.makeValue")
        EntityDatasourceFactory edf = datasourceFactoryByGroupMap.get(getEntityGroupName(entityName))
        return edf.makeEntityValue(entityName)
    }

    @Override
    EntityFind makeFind(String entityName) {
        if (!entityName) throw new EntityException("No entityName passed to EntityFacade.makeFind")
        EntityDatasourceFactory edf = datasourceFactoryByGroupMap.get(getEntityGroupName(entityName))
        return edf.makeEntityFind(entityName)
    }

    @Override
    EntityListIterator sqlFind(String sql, List<Object> sqlParameterList, String entityName, List<String> fieldList) {
        EntityDefinition ed = this.getEntityDefinition(entityName)
        this.entityDbMeta.checkTableRuntime(ed)

        Connection con = getConnection(getEntityGroupName(entityName))
        PreparedStatement ps
        try {
            // create the PreparedStatement
            ps = con.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            // set the parameter values
            int paramIndex = 1
            for (Object parameterValue in sqlParameterList) {
                EntityQueryBuilder.setPreparedStatementValue(ps, paramIndex, parameterValue, ed, this)
                paramIndex++
            }
            // do the actual query
            long timeBefore = System.currentTimeMillis()
            ResultSet rs = ps.executeQuery()
            if (logger.traceEnabled) logger.trace("Executed query with SQL [${sql}] and parameters [${sqlParameterList}] in [${(System.currentTimeMillis()-timeBefore)/1000}] seconds")
            // make and return the eli
            ListOrderedSet fieldLos = new ListOrderedSet()
            fieldLos.addAll(fieldList)
            EntityListIterator eli = new EntityListIteratorImpl(con, rs, ed, fieldLos, this)
            return eli
        } catch (SQLException e) {
            throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e)
        }
    }

    Map<String, Map> getDataDocumentEntityInfo(String dataDocumentId) {
        EntityValue dataDocument = makeFind("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).useCache(true).one()
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)

        String primaryEntityName = dataDocument.primaryEntityName
        EntityDefinition primaryEd = getEntityDefinition(primaryEntityName)

        Map<String, Map> entityInfoMap = [:]

        // start with the primary entity
        entityInfoMap.put(primaryEntityName, [:])

        // have to go through entire fieldTree instead of entity names directly from fieldPath because may not have hash (#) separator
        Map<String, Object> fieldTree = [:]
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = dataDocumentField.fieldPath
            Iterator<String> fieldPathElementIter = fieldPath.split(":").iterator()
            Map currentTree = fieldTree
            Map currentEntityInfo = entityInfoMap.get(primaryEntityName)
            EntityDefinition currentEd = primaryEd
            while (fieldPathElementIter.hasNext()) {
                String fieldPathElement = fieldPathElementIter.next()
                if (fieldPathElementIter.hasNext()) {
                    Map subTree = (Map) currentTree.get(fieldPathElement)
                    if (subTree == null) { subTree = [:]; currentTree.put(fieldPathElement, subTree) }
                    currentTree = subTree
                    // make sure we have an entityInfo Map
                    Node relNode = currentEd.getRelationshipNode(fieldPathElement)
                    if (relNode == null) throw new EntityException("Could not find relationship [${fieldPathElement}] from entity [${currentEd.getFullEntityName()}] as part of DataDocumentField.fieldPath [${fieldPath}]")
                    String relEntityName = relNode."@related-entity-name"
                    if (!entityInfoMap.containsKey(relEntityName)) entityInfoMap.put(relEntityName, [:])
                    currentEntityInfo = entityInfoMap.get(relEntityName)
                    currentEd = getEntityDefinition(relEntityName)
                } else {
                    currentTree.put(fieldPathElement, dataDocumentField.fieldNameAlias ?: fieldPathElement)
                    // save the current field name (not the alias)
                    StupidUtilities.addToSetInMap("fields", fieldPathElement, currentEntityInfo)
                    // see if there are any conditions for this alias, if so add the fieldName/value to the entity conditions Map
                    for (EntityValue dataDocumentCondition in dataDocumentConditionList) {
                        if (dataDocumentCondition.fieldNameAlias == dataDocumentField.fieldNameAlias) {
                            Map conditions = (Map) currentEntityInfo.get("conditions")
                            if (conditions == null) {
                                conditions = [:]
                                currentEntityInfo.put("conditions", conditions)
                            }
                            conditions.put(fieldPathElement, dataDocumentCondition.fieldValue)
                        }
                    }
                }
            }
        }
        logger.warn("============ got entityInfoMap: ${entityInfoMap}\n============ for fieldTree: ${fieldTree}")

        return entityInfoMap
    }

    @Override
    List<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp,
                                Timestamp thruUpdatedStamp) {
        EntityValue dataDocument = makeFind("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).useCache(true).one()
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)

        String primaryEntityName = dataDocument.primaryEntityName
        EntityDefinition primaryEd = getEntityDefinition(primaryEntityName)
        List<String> primaryPkFieldNames = primaryEd.getPkFieldNames()

        // build the field tree, nested Maps for relationship field path elements and field alias String for field name path elements
        Map<String, Object> fieldTree = [:]
        Map<String, String> fieldAliasPathMap = [:]
        populateFieldTreeAndAliasPathMap(dataDocumentFieldList, primaryPkFieldNames, fieldTree, fieldAliasPathMap)
        logger.warn("=========== ${dataDocumentId} fieldTree=${fieldTree}")
        logger.warn("=========== ${dataDocumentId} fieldAliasPathMap=${fieldAliasPathMap}")

        // build the query condition for the primary entity and all related entities
        EntityFind mainFind = makeFind(primaryEntityName)
        EntityDynamicView dynamicView = mainFind.makeEntityDynamicView()

        // add member entities and field aliases to dynamic view
        dynamicView.addMemberEntity("PRIM_ENT", primaryEntityName, null, null, null)
        fieldTree.put("_ALIAS", "PRIM_ENT")
        StupidUtilities.Incrementer incrementer = new StupidUtilities.Incrementer()
        addDataDocRelatedEntity(dynamicView, "PRIM_ENT", fieldTree, incrementer)

        // logger.warn("=========== ${dataDocumentId} fieldAliasPathMap=${((EntityDynamicViewImpl) dynamicView).getViewEntityNode()}")

        // add conditions
        if (condition) mainFind.condition(condition)
        for (EntityValue dataDocumentCondition in dataDocumentConditionList)
            mainFind.condition((String) dataDocumentCondition.fieldNameAlias, dataDocumentCondition.fieldValue)

        // create a condition with an OR list of date range comparisons to check that at least one member-entity has lastUpdatedStamp in range
        if (fromUpdateStamp != null || thruUpdatedStamp != null) {
            List<EntityCondition> dateRangeOrCondList = []
            for (Node memberEntityNode in dynamicView.getMemberEntityNodes()) {
                ConditionField ludCf = new ConditionField((String) memberEntityNode."@entity-alias",
                        "lastUpdatedStamp", getEntityDefinition((String) memberEntityNode."@entity-name"))
                List<EntityCondition> dateRangeFieldCondList = []
                if (fromUpdateStamp != null) {
                    dateRangeFieldCondList.add(getConditionFactory().makeCondition(
                            new FieldValueCondition(entityConditionFactory, ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(entityConditionFactory, ludCf, EntityCondition.GREATER_THAN_EQUAL_TO, fromUpdateStamp)))
                }
                if (thruUpdatedStamp != null) {
                    dateRangeFieldCondList.add(getConditionFactory().makeCondition(
                            new FieldValueCondition(entityConditionFactory, ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(entityConditionFactory, ludCf, EntityCondition.LESS_THAN, thruUpdatedStamp)))
                }
                dateRangeOrCondList.add(getConditionFactory().makeCondition(dateRangeFieldCondList, EntityCondition.AND))
            }
            mainFind.condition(getConditionFactory().makeCondition(dateRangeOrCondList, EntityCondition.OR))
        }
        logger.warn("=========== ${dataDocumentId} mainFind.condition=${((EntityFindImpl) mainFind).getWhereEntityCondition()}")

        // do the one big query
        EntityListIterator mainEli = mainFind.iterator()
        Map<String, Map> documentMapMap = [:]
        try {
            for (EntityValue ev in mainEli) {
                StringBuffer pkCombinedSb = new StringBuffer()
                for (String pkFieldName in primaryPkFieldNames) {
                    if (pkCombinedSb.length() > 0) pkCombinedSb.append("::")
                    pkCombinedSb.append(ev.get(pkFieldName))
                }
                String docId = pkCombinedSb.toString()

                /*
                  - _index = DataDocument.indexName
                  - _type = dataDocumentId
                  - _id = pk field values from primary entity, double colon separated
                  - _timestamp = document created time
                  - Map for primary entity with primaryEntityName as key
                  - nested List of Maps for each related entity with aliased fields with relationship name as key
                 */
                Map<String, Object> docMap = documentMapMap.get(docId)
                if (docMap == null) {
                    // add special entries
                    docMap = (Map<String, Object>) [_type:dataDocumentId, _id:docId]
                    docMap.put("_timestamp", ecfi.getL10nFacade().formatValue(
                            thruUpdatedStamp ?: new Timestamp(System.currentTimeMillis()), "yyyy-MM-dd'T'HH:mm:ss"))
                    if (dataDocument.indexName) docMap.put("_index", (String) dataDocument.indexName)
                    // add Map for primary entity
                    Map primaryEntityMap = [:]
                    for (Map.Entry fieldTreeEntry in fieldTree.entrySet()) {
                        if (fieldTreeEntry.getValue() instanceof String) {
                            if (fieldTreeEntry.getKey() == "_ALIAS") continue
                            String fieldName = fieldTreeEntry.getValue()
                            primaryEntityMap.put(fieldName, ev.get(fieldName))
                        }
                    }
                    docMap.put(primaryEntityName, primaryEntityMap)

                    documentMapMap.put(docId, docMap)
                }

                // recursively add List of Maps for each related entity
                populateDataDocRelatedMap(ev, docMap, fieldTree, false)
            }
        } finally {
            mainEli.close()
        }

        // make the actual list and return it
        List<Map> documentMapList = []
        for (Map.Entry<String, Map> documentMapEntry in documentMapMap.entrySet()) {
            Map docMap = documentMapEntry.getValue()
            // call the manualDataServiceName service for each document
            if (dataDocument.manualDataServiceName) {
                Map result = ecfi.getServiceFacade().sync().name((String) dataDocument.manualDataServiceName)
                        .parameters([dataDocumentId:dataDocumentId, document:docMap]).call()
                if (result.document) docMap = document
            }
            documentMapList.add(docMap)
        }
        return documentMapList
    }

    void populateFieldTreeAndAliasPathMap(EntityList dataDocumentFieldList, List<String> primaryPkFieldNames,
                                          Map<String, Object> fieldTree, Map<String, String> fieldAliasPathMap) {
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = dataDocumentField.fieldPath
            Iterator<String> fieldPathElementIter = fieldPath.split(":").iterator()
            Map currentTree = fieldTree
            while (fieldPathElementIter.hasNext()) {
                String fieldPathElement = fieldPathElementIter.next()
                if (fieldPathElementIter.hasNext()) {
                    Map subTree = (Map) currentTree.get(fieldPathElement)
                    if (subTree == null) { subTree = [:]; currentTree.put(fieldPathElement, subTree) }
                    currentTree = subTree
                } else {
                    currentTree.put(fieldPathElement, dataDocumentField.fieldNameAlias ?: fieldPathElement)
                    fieldAliasPathMap.put((String) dataDocumentField.fieldNameAlias ?: fieldPathElement, (String) dataDocumentField.fieldPath)
                }
            }
        }
        // make sure all PK fields of the primary entity are aliased
        for (String pkFieldName in primaryPkFieldNames) if (!fieldAliasPathMap.containsKey(pkFieldName)) {
            fieldTree.put(pkFieldName, pkFieldName)
            fieldAliasPathMap.put(pkFieldName, pkFieldName)
        }
    }

    protected void populateDataDocRelatedMap(EntityValue ev, Map<String, Object> parentDocMap, Map fieldTreeCurrent,
                                             boolean setFields) {
        for (Map.Entry fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            if (fieldTreeEntry.getKey() instanceof String && fieldTreeEntry.getKey() == "_ALIAS") continue
            if (fieldTreeEntry.getValue() instanceof Map) {
                String relationshipName = fieldTreeEntry.getKey()
                Map fieldTreeChild = (Map) fieldTreeEntry.getValue()

                // see if there is a Map in the List in the matching entry
                List<Map> relatedEntityDocList = (List<Map>) parentDocMap.get(relationshipName)
                Map relatedEntityDocMap = null
                if (relatedEntityDocList != null) {
                    for (Map candidateMap in relatedEntityDocList) {
                        boolean allMatch = true
                        for (Map.Entry fieldTreeChildEntry in fieldTreeChild.entrySet()) {
                            if (fieldTreeEntry.getValue() instanceof String) {
                                if (fieldTreeEntry.getKey() == "_ALIAS") continue
                                String fieldName = fieldTreeEntry.getValue()
                                if (candidateMap.get(fieldName) != ev.get(fieldName)) {
                                    allMatch = false
                                    break
                                }
                            }
                        }
                        if (allMatch) {
                            relatedEntityDocMap = candidateMap
                            break
                        }
                    }
                }
                boolean recurseSetFields = true
                if (relatedEntityDocMap == null) {
                    // no matching Map? create a new one... and it will get populated in the recursive call
                    if (relatedEntityDocList == null) {
                        relatedEntityDocList = []
                        parentDocMap.put(relationshipName, relatedEntityDocList)
                    }

                    relatedEntityDocMap = [:]
                    relatedEntityDocList.add(relatedEntityDocMap)
                } else {
                    recurseSetFields = false
                }

                // now time to recurse
                populateDataDocRelatedMap(ev, relatedEntityDocMap, fieldTreeChild, recurseSetFields)
            } else {
                if (setFields) {
                    // set the field
                    String fieldName = fieldTreeEntry.getValue()
                    parentDocMap.put(fieldName, ev.get(fieldName))
                }
            }
        }
    }

    protected void addDataDocRelatedEntity(EntityDynamicView dynamicView, String parentEntityAlias,
                                           Map fieldTreeCurrent, StupidUtilities.Incrementer incrementer) {
        for (Map.Entry fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            if (fieldTreeEntry.getKey() instanceof String && fieldTreeEntry.getKey() == "_ALIAS") continue
            if (fieldTreeEntry.getValue() instanceof Map) {
                // add member entity, and entity alias in "_ALIAS" entry
                String entityAlias = "MEMBER${incrementer.getAndIncrement()}"
                Map fieldTreeChild = (Map) fieldTreeEntry.getValue()
                dynamicView.addRelationshipMember(entityAlias, parentEntityAlias, (String) fieldTreeEntry.getKey(), true)
                fieldTreeChild.put("_ALIAS", entityAlias)
                // now time to recurse
                addDataDocRelatedEntity(dynamicView, entityAlias, fieldTreeChild, incrementer)
            } else {
                // add alias for field
                String entityAlias = fieldTreeCurrent.get("_ALIAS")
                dynamicView.addAlias(entityAlias, (String) fieldTreeEntry.getValue(), (String) fieldTreeEntry.getKey(), null)
            }
        }
    }

    /* Find and assemble data documents represented by a Map that can be easily turned into a JSON document. This is
     * used for searching by the Data Search feature and for data feeds to other systems with the Data Feed feature.
     *
     * @param dataDocumentId Used to look up the DataDocument and related records (DataDocument* entities).
     * @param condition An optional condition to AND with from/thru updated timestamps and any DataDocumentCondition
     *                  records associated with the DataDocument.
     * @param fromUpdateStamp The lastUpdatedStamp on at least one entity selected must be after (>=) this Timestamp.
     * @param thruUpdatedStamp The lastUpdatedStamp on at least one entity selected must be before (<) this Timestamp.
     * @return List of Maps with these entries:
     *      - _index = DataDocument.indexName
     *      - _type = dataDocumentId
     *      - _id = pk field values from primary entity, underscore separated
     *      - Map for primary entity (with primaryEntityName as key)
     *      - nested List of Maps for each related entity from DataDocumentField records with aliased fields
     *          (with relationship name as key)
     */

    @Override
    String sequencedIdPrimary(String seqName, Long staggerMax, Long bankSize) {
        try {
            // is the seqName an entityName?
            EntityDefinition ed = getEntityDefinition(seqName)
            if (ed != null) {
                String groupName = getEntityGroupName(ed)
                if (ed.getEntityNode()?."@sequence-primary-use-uuid" == "true" ||
                        getDatasourceNode(groupName)?."@sequence-primary-use-uuid" == "true")
                    return UUID.randomUUID().toString()
            }
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
        }
        // fall through to default to the db sequenced ID
        return dbSequencedIdPrimary(seqName, staggerMax, bankSize)
    }

    @Override
    protected synchronized String dbSequencedIdPrimary(String seqName, Long staggerMax, Long bankSize) {
        // TODO: find some way to get this running non-synchronized for performance reasons (right now if not
        // TODO:     synchronized the forUpdate won't help if the record doesn't exist yet, causing errors in high
        // TODO:     traffic creates; is it creates only?)

        // NOTE: simple approach with forUpdate, not using the update/select "ethernet" approach used in OFBiz; consider
        // that in the future if there are issues with this approach

        // first get a bank if we don't have one already
        ArrayList<Long> bank = (ArrayList) this.entitySequenceBankCache.get(seqName)
        if (bank == null || bank[0] == null || bank[0] > bank[1]) {
            if (bank == null) {
                bank = new ArrayList<Long>(2)
                this.entitySequenceBankCache.put(seqName, bank)
            }

            TransactionFacade tf = this.ecfi.getTransactionFacade()
            boolean suspendedTransaction = false
            try {
                if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
                boolean beganTransaction = tf.begin(null)
                try {
                    EntityValue svi = makeFind("moqui.entity.SequenceValueItem").condition("seqName", seqName)
                            .useCache(false).forUpdate(true).one()
                    if (svi == null) {
                        svi = makeValue("moqui.entity.SequenceValueItem")
                        svi.set("seqName", seqName)
                        // a new tradition: start sequenced values at one hundred thousand instead of ten thousand
                        bank[0] = 100000
                        bank[1] = bank[0] + ((bankSize ?: 1) - 1)
                        svi.set("seqNum", bank[1])
                        svi.create()
                    } else {
                        Long lastSeqNum = svi.getLong("seqNum")
                        bank[0] = lastSeqNum + 1
                        bank[1] = bank[0] + ((bankSize ?: 1) - 1)
                        svi.set("seqNum", bank[1])
                        svi.update()
                    }
                } catch (Throwable t) {
                    tf.rollback(beganTransaction, "Error getting primary sequenced ID", t)
                } finally {
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                }
            } catch (TransactionException e) {
                throw e
            } finally {
                if (suspendedTransaction) tf.resume()
            }
        }

        Long seqNum = bank[0]
        if (staggerMax) {
            long stagger = Math.round(Math.random() * staggerMax)
            if (stagger == 0) stagger = 1
            bank[0] += stagger
            // NOTE: if bank[0] > bank[1] because of this just leave it and the next time we try to get a sequence
            //     value we'll get one from a new bank
        } else {
            bank[0] += 1
        }

        String prefix = this.ecfi.getConfXmlRoot()."entity-facade"[0]."@sequenced-id-prefix"
        return (prefix?:"") + seqNum.toString()
    }

    String getEntityGroupName(EntityDefinition ed) {
        String entityName = ed.getFullEntityName()
        Node entityNode = ed.getEntityNode()
        if (entityNode."@is-dynamic-view" == "true") {
            // use the name of the first member-entity
            entityName = entityNode."member-entity".find({ !it."@join-from-alias" })?."@entity-name"
        }
        return getEntityGroupName(entityName)
    }

    @Override
    String getEntityGroupName(String entityName) {
        String entityGroupName = entityGroupNameMap.get(entityName)
        if (entityGroupName != null) return entityGroupName
        EntityDefinition ed = this.getEntityDefinition(entityName)
        if (!ed) return null
        if (ed.entityNode."@group-name") {
            entityGroupName = ed.entityNode."@group-name"
        } else {
            entityGroupName = this.ecfi.getConfXmlRoot()."entity-facade"[0]."@default-group-name"
        }
        entityGroupNameMap.put(entityName, entityGroupName)
        return entityGroupName
    }

    @Override
    Connection getConnection(String groupName) {
        EntityDatasourceFactory edf = this.datasourceFactoryByGroupMap.get(groupName)
        DataSource ds = edf.getDataSource()
        if (ds == null) throw new EntityException("Cannot get JDBC Connection for group-name [${groupName}] because it has no DataSource")
        if (ds instanceof XADataSource) {
            return this.ecfi.transactionFacade.enlistConnection(((XADataSource) ds).getXAConnection())
        } else {
            return ds.getConnection()
        }
    }

    @Override
    EntityDataLoader makeDataLoader() { return new EntityDataLoaderImpl(this) }

    @Override
    EntityDataWriter makeDataWriter() { return new EntityDataWriterImpl(this) }

    @Override
    EntityValue makeValue(Element element) {
        if (!element) return null

        String entityName = element.getTagName()
        if (entityName.indexOf('-') > 0) entityName = entityName.substring(entityName.indexOf('-') + 1)
        if (entityName.indexOf(':') > 0) entityName = entityName.substring(entityName.indexOf(':') + 1)

        EntityValue newValue = makeValue(entityName)
        EntityDefinition ed = newValue.getEntityDefinition()

        for (String fieldName in ed.getAllFieldNames()) {
            String attrValue = element.getAttribute(fieldName)
            if (attrValue) {
                newValue.setString(fieldName, attrValue)
            } else {
                org.w3c.dom.NodeList seList = element.getElementsByTagName(fieldName)
                Element subElement = seList.getLength() > 0 ? (Element) seList.item(0) : null
                if (subElement) newValue.setString(fieldName, StupidUtilities.elementValue(subElement))
            }
        }

        return newValue
    }

    protected Map<String, Map<String, String>> javaTypeByGroup = [:]
    String getFieldJavaType(String fieldType, EntityDefinition ed) {
        String groupName = this.getEntityGroupName(ed)
        Map<String, String> javaTypeMap = javaTypeByGroup.get(groupName)
        if (javaTypeMap == null) {
            javaTypeMap = new HashMap()
            javaTypeByGroup.put(groupName, javaTypeMap)
        } else {
            String ft = javaTypeMap.get(fieldType)
            if (ft != null) return ft
        }

        Node databaseNode = this.getDatabaseNode(groupName)
        String javaType = databaseNode ? databaseNode."database-type".find({ it.@type == fieldType })?."@java-type" : null
        if (!javaType) {
            Node databaseListNode = this.ecfi.confXmlRoot."database-list"[0]
            javaType = databaseListNode ? databaseListNode."dictionary-type".find({ it.@type == fieldType })?."@java-type" : null
            if (!javaType) throw new EntityException("Could not find Java type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        javaTypeMap.put(fieldType, javaType)
        return javaType
    }

    protected Map<String, Map<String, String>> sqlTypeByGroup = [:]
    protected String getFieldSqlType(String fieldType, EntityDefinition ed) {
        String groupName = this.getEntityGroupName(ed)
        Map<String, String> sqlTypeMap = sqlTypeByGroup.get(groupName)
        if (sqlTypeMap == null) {
            sqlTypeMap = new HashMap()
            sqlTypeByGroup.put(groupName, sqlTypeMap)
        } else {
            String st = sqlTypeMap.get(fieldType)
            if (st != null) return st
        }

        Node databaseNode = this.getDatabaseNode(groupName)
        String sqlType = databaseNode ? databaseNode."database-type".find({ it.@type == fieldType })?."@sql-type" : null
        if (!sqlType) {
            Node databaseListNode = this.ecfi.confXmlRoot."database-list"[0]
            sqlType = databaseListNode ? databaseListNode."dictionary-type".find({ it.@type == fieldType })?."@default-sql-type" : null
            if (!sqlType) throw new EntityException("Could not find SQL type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        sqlTypeMap.put(fieldType, sqlType)
        return sqlType
    }

    protected static final Map<String, Integer> javaIntTypeMap = [
            "java.lang.String":1, "String":1, "org.codehaus.groovy.runtime.GStringImpl":1,
            "java.sql.Timestamp":2, "Timestamp":2,
            "java.sql.Time":3, "Time":3,
            "java.sql.Date":4, "Date":4,
            "java.lang.Integer":5, "Integer":5,
            "java.lang.Long":6,"Long":6,
            "java.lang.Float":7, "Float":7,
            "java.lang.Double":8, "Double":8,
            "java.math.BigDecimal":9, "BigDecimal":9,
            "java.lang.Boolean":10, "Boolean":10,
            "java.lang.Object":11, "Object":11,
            "java.sql.Blob":12, "Blob":12, "byte[]":12, "java.nio.ByteBuffer":12, "java.nio.HeapByteBuffer":12,
            "java.sql.Clob":13, "Clob":13,
            "java.util.Date":14,
            "java.util.ArrayList":15, "java.util.HashSet":15, "java.util.LinkedHashSet":15, "java.util.LinkedList":15]
    public static int getJavaTypeInt(String javaType) {
        Integer typeInt = javaIntTypeMap[javaType]
        if (!typeInt) throw new EntityException("Java type " + javaType + " not supported for entity fields")
        return typeInt
    }
}
