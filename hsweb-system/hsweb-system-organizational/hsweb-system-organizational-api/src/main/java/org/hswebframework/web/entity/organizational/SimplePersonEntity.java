/*
 *  Copyright 2019 http://www.hswebframework.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.hswebframework.web.entity.organizational;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.validator.constraints.Email;
import org.hibernate.validator.constraints.NotBlank;
import org.hswebframework.web.commons.entity.SimpleGenericEntity;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

/**
 * 人员
 *
 * @author hsweb-generator-online
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "s_person", indexes = {
        @Index(name = "idx_person_user_id", columnList = "user_id")
})
public class SimplePersonEntity extends SimpleGenericEntity<String> implements PersonEntity {
    private static final long serialVersionUID = -4232153898188508965L;
    //姓名
    @NotBlank
    @Column
    private String name;
    //性别
    @Column
    private Byte sex;
    //电子邮箱
    @Email
    @Column
    private String email;
    //联系电话
    @Column
    private String phone;
    //照片
    @Column
    private String photo;
    //关联用户id
    @Column(name = "user_id", length = 32)
    private String userId;
    //状态
    @Column
    private Byte status;
    //备注
    @Column
    private String remark;

    @Override
    @Id
    @Column(name = "u_id")
    public String getId() {
        return super.getId();
    }
}