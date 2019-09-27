package org.hswebframework.web.service.organizational.simple.terms;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.PrepareSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.web.service.organizational.PositionService;

import java.util.List;


/**
 * 查询岗位中的用户
 *
 * @author zhouhao
 * @since 3.0.0-RC
 */
public class UserInPositionSqlTerm extends UserInSqlTerm {


    public UserInPositionSqlTerm(String term, PositionService positionService) {
        super(term, positionService);

    }

    @Override
    public String getTableName() {
        return "_pos";
    }

    @Override
    public SqlFragments createFragments(String columnFullName, RDBColumnMetadata column, Term term) {
        PrepareSqlFragments fragments = PrepareSqlFragments.of();

        boolean not = term.getOptions().contains("not");
        boolean child = term.getOptions().contains("child");
        boolean parent = term.getOptions().contains("parent");


        fragments.addSql(not ? "not" : "", "exists(select 1 from ", getTableFullName("s_person_position"), " _tmp");
        if (child || parent) {
            fragments.addSql(",", getTableFullName("s_position"), " _pos");
        }
        if (!isForPerson()) {
            fragments.addSql(",", getTableFullName("s_person"), " _person");
        }
        fragments.addSql("where ",
                columnFullName, "=",
                isForPerson() ? " _tmp.person_id" : "_person.user_id and _person.u_id=_tmp.person_id");
        if (child || parent) {
            fragments.addSql("and _pos.u_id=_tmp.position_id");
        }
        List<Object> positionIdList = convertList(term.getValue());
        if (!positionIdList.isEmpty()) {
            fragments.addSql("and");
            appendCondition("_tmp.position_id", fragments, column,term, positionIdList);
        }
        fragments.addSql(")");
        return fragments;
    }

    @Override
    public String getName() {
        return "根据" + (isForPerson() ? "人员" : "用户") + "按岗位查询";
    }
}
