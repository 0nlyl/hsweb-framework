package org.hswebframework.web.service.form.simple;

import com.alibaba.fastjson.JSON;
import org.hswebframework.ezorm.core.OriginalValueCodec;
import org.hswebframework.ezorm.core.RuntimeDefaultValue;
import org.hswebframework.ezorm.core.ValueCodec;
import org.hswebframework.ezorm.rdb.codec.BlobValueCodec;
import org.hswebframework.ezorm.rdb.codec.DateTimeCodec;
import org.hswebframework.ezorm.rdb.codec.JsonValueCodec;
import org.hswebframework.ezorm.rdb.codec.NumberValueCodec;
import org.hswebframework.ezorm.rdb.mapping.SyncRepository;
import org.hswebframework.ezorm.rdb.mapping.defaults.record.Record;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.NativeSql;
import org.hswebframework.web.NotFoundException;
import org.hswebframework.web.commons.entity.DataStatus;
import org.hswebframework.web.dict.EnumDict;
import org.hswebframework.web.entity.form.*;
import org.hswebframework.web.id.IDGenerator;
import org.hswebframework.web.service.DefaultDSLDeleteService;
import org.hswebframework.web.service.DefaultDSLQueryService;
import org.hswebframework.web.service.DefaultDSLUpdateService;
import org.hswebframework.web.service.GenericEntityService;
import org.hswebframework.web.service.form.*;
import org.hswebframework.web.service.form.events.FormDeployEvent;
import org.hswebframework.web.service.form.initialize.DynamicFormInitializeCustomizer;
import org.hswebframework.web.service.form.simple.dict.EnumDictValueConverter;
import org.hswebframework.web.validator.group.CreateGroup;
import org.hswebframework.web.validator.group.UpdateGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.JDBCType;
import java.sql.SQLType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc;

/**
 * 默认的服务实现
 *
 * @author hsweb-generator-online
 */
@Service("dynamicFormService")
@CacheConfig(cacheNames = "dyn-form")
public class SimpleDynamicFormService extends GenericEntityService<DynamicFormEntity, String>
        implements DynamicFormService, FormDeployService {

    @Value("${hsweb.dynamic-form.tags:none}")
    private String[] tags;

    @Value("${hsweb.dynamic-form.tag:none}")
    private String tag;

    @Value("${hsweb.dynamic-form.load-only-tags:null}")
    private String[] loadOnlyTags;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private DynamicFormDeployLogService dynamicFormDeployLogService;

    @Autowired(required = false)
    private OptionalConvertBuilder optionalConvertBuilder;

    @Autowired(required = false)
    private List<DynamicFormInitializeCustomizer> initializeCustomizers;

    @Autowired
    private SyncRepository<DynamicFormColumnEntity, String> formColumnDao;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private Map<String, SyncRepository<Record, String>> formRepositoryCache = new ConcurrentHashMap<>();

    @Override
    protected IDGenerator<String> getIDGenerator() {
        return IDGenerator.MD5;
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "dyn-form-deploy", allEntries = true),
            @CacheEvict(value = "dyn-form", allEntries = true),
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deployAllFromLog() {

        List<String> tags = new ArrayList<>(Arrays.asList(this.tags));
        if (loadOnlyTags != null) {
            tags.addAll(Arrays.asList(loadOnlyTags));
        }
        List<DynamicFormEntity> entities = createQuery()
                .select(DynamicFormEntity.id)
                .where(DynamicFormEntity.deployed, true)
                .and()
                .in(DynamicFormEntity.tags, tags)
                .fetch();
        if (logger.isDebugEnabled()) {
            logger.debug("do deploy all form , size:{}", entities.size());
        }
        for (DynamicFormEntity form : entities) {
            DynamicFormDeployLogEntity logEntity = dynamicFormDeployLogService.selectLastDeployed(form.getId());
            if (null != logEntity) {
                deployFromLog(logEntity);
            }
        }
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "dyn-form-deploy", allEntries = true),
            @CacheEvict(value = "dyn-form", allEntries = true),
    })
    public void deployAll() {
        createQuery()
                .select(DynamicFormEntity.id)
                .fetch()
                .forEach(form -> this.deploy(form.getId()));
    }

    public DynamicFormDeployLogEntity createDeployLog(DynamicFormEntity form, List<DynamicFormColumnEntity> columns) {
        DynamicFormDeployLogEntity entity = entityFactory.newInstance(DynamicFormDeployLogEntity.class);
        entity.setStatus(DataStatus.STATUS_ENABLED);
        entity.setDeployTime(System.currentTimeMillis());
        entity.setVersion(form.getVersion());
        entity.setFormId(form.getId());
        DynamicFormColumnBindEntity bindEntity = new DynamicFormColumnBindEntity();
        bindEntity.setForm(form);
        bindEntity.setColumns(columns);
        entity.setMetaData(JSON.toJSONString(bindEntity));
        return entity;
    }


    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deployFromLog(DynamicFormDeployLogEntity logEntity) {
        DynamicFormColumnBindEntity entity = JSON.parseObject(logEntity.getMetaData(), DynamicFormColumnBindEntity.class);
        DynamicFormEntity form = entity.getForm();
        List<DynamicFormColumnEntity> columns = entity.getColumns();
        if (logger.isDebugEnabled()) {
            logger.debug("do deploy form {} , columns size:{}", form.getName(), columns.size());
        }

        deploy(form, columns, !(loadOnlyTags != null && Arrays.asList(loadOnlyTags).contains(entity.getForm().getTags())));
    }


    @Override
    @CacheEvict(key = "'form_id:'+#entity.id")
    public String insert(DynamicFormEntity entity) {
        entity.setDeployed(false);
        entity.setVersion(1L);
        entity.setCreateTime(System.currentTimeMillis());
        entity.setTags(tag);
        return super.insert(entity);
    }

    @Override
    @Cacheable(key = "'form_id:'+#id")
    public DynamicFormEntity selectByPk(String id) {
        return super.selectByPk(id);
    }

    @Override
    @CacheEvict(key = "'form_id:'+#id")
    public int updateByPk(String id, DynamicFormEntity entity) {
        entity.setVersion(null);
        entity.setDeployed(null);
        entity.setUpdateTime(System.currentTimeMillis());
        getDao().createUpdate()
                .set(DynamicFormEntity::getVersion, NativeSql.of("version+1"))
                .execute();
        return super.updateByPk(id, entity);
    }

    protected void initDatabase(RDBTableMetadata database) {

    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "dyn-form-deploy", allEntries = true),
            @CacheEvict(value = "dyn-form", allEntries = true),
    })
    public void unDeploy(String formId) {
        DynamicFormEntity form = selectByPk(formId);
        assertNotNull(form);
        //取消发布
        dynamicFormDeployLogService.cancelDeployed(formId);
        //移除表结构定义
        DatabaseOperator database = StringUtils.isEmpty(form.getDataSourceId())
                ? databaseRepository.getDefaultDatabase(form.getDatabaseName())
                : databaseRepository.getDatabase(form.getDataSourceId(), form.getDatabaseName());
        database.getMetadata().getCurrentSchema().removeTableOrView(form.getDatabaseTableName());
        createUpdate().set(DynamicFormEntity.deployed, false)
                .where(DynamicFormEntity.id, formId)
                .execute();
        eventPublisher.publishEvent(new FormDeployEvent(formId));
        formRepositoryCache.remove(formId);
    }

    private String saveOrUpdate0(DynamicFormColumnEntity columnEntity) {
        if (StringUtils.isEmpty(columnEntity.getId())
                || DefaultDSLQueryService.createQuery(formColumnDao)
                .where(
                        DynamicFormColumnEntity.id, columnEntity.getId())
                .count() == 0) {
            if (StringUtils.isEmpty(columnEntity.getId())) {
                columnEntity.setId(getIDGenerator().generate());
            }
            tryValidate(columnEntity, CreateGroup.class);
            formColumnDao.insert(columnEntity);
        } else {
            tryValidate(columnEntity, UpdateGroup.class);
            DefaultDSLUpdateService
                    .createUpdate(formColumnDao, columnEntity)
                    .where(DynamicFormColumnEntity.id, columnEntity.getId())
                    .execute();
        }
        return columnEntity.getId();
    }

    @Override
    @Caching(
            evict = {
                    @CacheEvict(key = "'form-columns:'+#columnEntity.formId"),
                    @CacheEvict(key = "'form_id:'+#columnEntity.formId"),

            }
    )
    public String saveOrUpdateColumn(DynamicFormColumnEntity columnEntity) {
        String id = saveOrUpdate0(columnEntity);
        getDao().createUpdate().set(DynamicFormEntity::getVersion, NativeSql.of("version+1")).execute();
        return id;
    }

    @Override
    @CacheEvict(allEntries = true)
    public List<String> saveOrUpdateColumn(List<DynamicFormColumnEntity> columnEntities) {
        Set<String> formId = new HashSet<>();

        List<String> columnIds = columnEntities.stream()
                .peek(columnEntity -> formId.add(columnEntity.getFormId()))
                .map(this::saveOrUpdateColumn)
                .collect(Collectors.toList());

        getDao().createUpdate().set(DynamicFormEntity::getVersion, NativeSql.of("version+1")).execute();
        return columnIds;

    }

    @Override
    @Caching(
            evict = {
                    @CacheEvict(key = "'form-columns:'+#result"),
                    @CacheEvict(key = "'form_id:'+#result"),

            }
    )
    public String saveOrUpdate(DynamicFormColumnBindEntity bindEntity) {
        DynamicFormEntity formEntity = bindEntity.getForm();

        List<DynamicFormColumnEntity> columnEntities = bindEntity.getColumns();
        //保存表单
        saveOrUpdate(formEntity);

        //保存表单列
        columnEntities.stream()
                .peek(column -> column.setFormId(formEntity.getId()))
                .forEach(this::saveOrUpdate0);

        return formEntity.getId();
    }

    @Override
    @Caching(
            evict = {
                    @CacheEvict(key = "'form-columns:'+#formId"),
                    @CacheEvict(key = "'form_id:'+#formId"),

            }
    )
    public DynamicFormColumnEntity deleteColumn(String formId) {
        DynamicFormColumnEntity oldColumn = DefaultDSLQueryService
                .createQuery(formColumnDao)
                .where(DynamicFormColumnEntity.id, formId)
                .fetchOne()
                .orElseThrow(() -> new NotFoundException("表单不存在"));

        assertNotNull(oldColumn);
        DefaultDSLDeleteService.createDelete(formColumnDao)
                .where(DynamicFormDeployLogEntity.id, formId)
                .execute();
        return oldColumn;
    }

    @Override
    @Caching(
            evict = {
                    @CacheEvict(key = "'form-columns:'+#id"),
                    @CacheEvict(key = "'form_id:'+#id")
            })
    public DynamicFormEntity deleteByPk(String id) {
        Objects.requireNonNull(id, "id can not be null");

        DefaultDSLDeleteService.createDelete(formColumnDao)
                .where(DynamicFormColumnEntity.formId, id)
                .execute();
        return super.deleteByPk(id);
    }

    @Override
    @CacheEvict(allEntries = true)
    public List<DynamicFormColumnEntity> deleteColumn(List<String> ids) {
        Objects.requireNonNull(ids);
        if (ids.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        List<DynamicFormColumnEntity> oldColumns = DefaultDSLQueryService
                .createQuery(formColumnDao)
                .where()
                .in(DynamicFormColumnEntity.id, ids)
                .fetch();

        DefaultDSLDeleteService.createDelete(formColumnDao)
                .where().in(DynamicFormDeployLogEntity.id, ids)
                .execute();
        return oldColumns;
    }

    @Override
    public List<DynamicFormColumnEntity> selectColumnsByFormId(String formId) {
        Objects.requireNonNull(formId);
        return DefaultDSLQueryService.createQuery(formColumnDao)
                .where(DynamicFormColumnEntity.formId, formId)
                .orderBy(asc(DynamicFormColumnEntity.sortIndex))
                .fetch();
    }

    @Override
    @Cacheable(value = "dyn-form-deploy", key = "'form-deploy:'+#formId+':'+#version")
    public DynamicFormColumnBindEntity selectDeployed(String formId, int version) {
        DynamicFormDeployLogEntity entity = dynamicFormDeployLogService.selectDeployed(formId, version);
        if (entity == null) {
            return null;
        }
        return JSON.parseObject(entity.getMetaData(), DynamicFormColumnBindEntity.class);
    }

    @Override
    @Cacheable(value = "dyn-form-deploy", key = "'form-deploy-version:'+#formId")
    public long selectDeployedVersion(String formId) {
        DynamicFormColumnBindEntity entity = selectLatestDeployed(formId);
        if (null != entity) {
            return entity.getForm().getVersion();
        }
        return 0L;
    }

    @Override
    public SyncRepository<Record, String> getRepository(String formId) {

        return formRepositoryCache.get(formId);
    }

    @Override
    @Cacheable(value = "dyn-form-deploy", key = "'form-deploy:'+#formId+':latest'")
    public DynamicFormColumnBindEntity selectLatestDeployed(String formId) {
        DynamicFormDeployLogEntity entity = dynamicFormDeployLogService.selectLastDeployed(formId);
        if (entity == null) {
            return null;
        }
        return JSON.parseObject(entity.getMetaData(), DynamicFormColumnBindEntity.class);
    }

    @Override
    public DynamicFormColumnBindEntity selectEditing(String formId) {
        Objects.requireNonNull(formId);
        return new DynamicFormColumnBindEntity(selectByPk(formId), selectColumnsByFormId(formId));
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "dyn-form-deploy", key = "'form-deploy-version:'+#formId"),
            @CacheEvict(value = "dyn-form-deploy", key = "'form-deploy:'+#formId+':latest'"),
            @CacheEvict(value = "dyn-form", allEntries = true)
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deploy(String formId) {
        DynamicFormEntity formEntity = selectByPk(formId);
        assertNotNull(formEntity);
        if (Boolean.TRUE.equals(formEntity.getDeployed())) {
            dynamicFormDeployLogService.cancelDeployed(formId);
        }
        List<DynamicFormColumnEntity> columns = selectColumnsByFormId(formId);
        deploy(formEntity, columns, true);
        createUpdate().set(DynamicFormEntity.deployed, true).where(DynamicFormEntity.id, formId).execute();
        try {
            dynamicFormDeployLogService.insert(createDeployLog(formEntity, columns));

            eventPublisher.publishEvent(new FormDeployEvent(formId));
        } catch (Exception e) {
            unDeploy(formId);
            throw e;
        }
    }

    public void deploy(DynamicFormEntity form, List<DynamicFormColumnEntity> columns, boolean updateMeta) {
        DatabaseOperator operator = StringUtils.isEmpty(form.getDataSourceId())
                ? databaseRepository.getDefaultDatabase(form.getDatabaseName())
                : databaseRepository.getDatabase(form.getDataSourceId(), form.getDatabaseName());

        RDBTableMetadata metadata = buildTable(operator.getMetadata().getCurrentSchema(), form, columns);

        operator.ddl()
                .createOrAlter(metadata)
                .commit()
                .sync();
        formRepositoryCache.put(form.getId(),operator.dml().createRepository(form.getDatabaseTableName()));
 
    }

    protected RDBTableMetadata buildTable(RDBSchemaMetadata schema, DynamicFormEntity form, List<DynamicFormColumnEntity> columns) {
        RDBTableMetadata table = schema.newTable(form.getDatabaseTableName());
        table.setComment(form.getDescribe());

        table.setAlias(form.getAlias());
        columns.forEach(column -> {
            RDBColumnMetadata columnMeta = table.newColumn();
            columnMeta.setName(column.getColumnName());
            columnMeta.setAlias(column.getAlias());
            columnMeta.setComment(column.getDescribe());
            columnMeta.setLength(column.getLength() == null ? 0 : column.getLength());
            columnMeta.setPrecision(column.getPrecision() == null ? 0 : column.getPrecision());
            columnMeta.setScale(column.getScale() == null ? 0 : column.getScale());
            columnMeta.setJdbcType(JDBCType.valueOf(column.getJdbcType()), getJavaType(column.getJavaType()));

            columnMeta.setProperties(column.getProperties() == null ? new HashMap<>() : column.getProperties());

            columnMeta.setValueCodec(initColumnValueConvert(columnMeta.getType().getSqlType(), columnMeta.getJavaType()));

            if (optionalConvertBuilder != null && null != column.getDictConfig()) {
                try {
                    DictConfig config = JSON.parseObject(column.getDictConfig(), DictConfig.class);
                    config.setColumn(columnMeta);

                    columnMeta.setDictionaryCodec(optionalConvertBuilder.build(config));
                    ValueCodec converter = optionalConvertBuilder.buildValueConverter(config);
                    if (null != converter) {
                        columnMeta.setValueCodec(converter);
                    }
                } catch (Exception e) {
                    logger.warn("创建字典转换器失败", e);
                }
            }
            table.addColumn(columnMeta);
        });

        //没有主键并且没有id字段
        if (table.getColumns().stream().noneMatch(RDBColumnMetadata::isPrimaryKey) && !table.findColumn("id").isPresent()) {
            table.addColumn(createPrimaryKeyColumn(table));
        }

        return table;
    }

    protected RDBColumnMetadata createPrimaryKeyColumn(RDBTableMetadata schemaMetadata) {
        RDBColumnMetadata id = schemaMetadata.newColumn();
        id.setName("id");
        id.setJdbcType(JDBCType.VARCHAR, String.class);
        id.setLength(32);
        id.setDefaultValue((RuntimeDefaultValue) IDGenerator.MD5::generate);
        id.setComment("主键");
        id.setPrimaryKey(true);
        id.setNotNull(true);
        id.setProperty("read-only", true);
        return id;
    }


    protected ValueCodec initColumnValueConvert(SQLType jdbcType, Class javaType) {
        boolean isBasicClass = !classMapping
                .values()
                .contains(javaType)
                && javaType != Map.class
                && javaType != List.class;

        if (javaType.isEnum() && EnumDict.class.isAssignableFrom(javaType)) {
            return new EnumDictValueConverter<EnumDict>(() -> (List) Arrays.asList(javaType.getEnumConstants()));
        }
        switch (((JDBCType) jdbcType)) {
            case BLOB:
                if (!isBasicClass) {
                    return JsonValueCodec.of(javaType);
                }
                return BlobValueCodec.INSTANCE;
            case NUMERIC:
            case BIGINT:
            case INTEGER:
            case SMALLINT:
            case TINYINT:
                return new NumberValueCodec(javaType);
            case DATE:
            case TIMESTAMP:
            case TIME:
                return new DateTimeCodec("yyyy-MM-dd HH:mm:ss", javaType);
            default:


                return OriginalValueCodec.INSTANCE;
        }

    }

    private static final Map<String, Class> classMapping = new HashMap<>();

    static {
        classMapping.put("string", String.class);
        classMapping.put("String", String.class);
        classMapping.put("int", Integer.class);
        classMapping.put("Integer", Integer.class);
        classMapping.put("byte", Byte.class);
        classMapping.put("Byte", Byte.class);

        classMapping.put("byte[]", Byte[].class);
        classMapping.put("Byte[]", Byte[].class);

        classMapping.put("short", Short.class);
        classMapping.put("Short", Short.class);
        classMapping.put("boolean", Boolean.class);
        classMapping.put("Boolean", Boolean.class);
        classMapping.put("double", Double.class);
        classMapping.put("Double", Double.class);
        classMapping.put("float", Float.class);
        classMapping.put("Float", Float.class);
        classMapping.put("long", Long.class);
        classMapping.put("Long", Long.class);
        classMapping.put("char", Character.class);
        classMapping.put("Char", Character.class);
        classMapping.put("char[]", Character[].class);
        classMapping.put("Char[]", Character[].class);

        classMapping.put("Character", Character.class);

        classMapping.put("BigDecimal", BigDecimal.class);
        classMapping.put("BigInteger", BigInteger.class);

        classMapping.put("map", Map.class);
        classMapping.put("Map", Map.class);
        classMapping.put("list", List.class);
        classMapping.put("List", List.class);

        classMapping.put("date", Date.class);
        classMapping.put("Date", Date.class);

    }

    private Class getJavaType(String type) {
        if (StringUtils.isEmpty(type)) {
            return String.class;
        }
        Class clazz = classMapping.get(type);
        if (clazz == null) {
            try {
                clazz = Class.forName(type);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return clazz;
    }

}
