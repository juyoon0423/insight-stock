package com.insightstock.insightstock.domain.support;

import com.pgvector.PGvector;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * pgvector의 {@code vector(n)} 컬럼과 {@code float[]} 사이를 매핑하는 Hibernate 6 UserType.
 * 값 변환 자체는 pgvector-java의 {@link PGvector}에 위임하고, 이 클래스는 JDBC 바인딩/추출
 * 지점만 연결한다.
 */
public class VectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) {
        return Arrays.equals(x, y);
    }

    @Override
    public int hashCode(float[] x) {
        return Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        Object columnValue = rs.getObject(position);
        if (columnValue == null) {
            return null;
        }
        // addVectorType()으로 커넥션을 등록해두지 않았으므로 드라이버는 PGobject로 반환한다.
        // PGvector(String)이 "[0.1,0.2,...]" 텍스트 표현 파싱을 그대로 담당한다.
        return new PGvector(columnValue.toString()).toArray();
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, new PGvector(value));
        }
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) throws HibernateException {
        return deepCopy(value);
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) throws HibernateException {
        return deepCopy((float[]) cached);
    }
}
