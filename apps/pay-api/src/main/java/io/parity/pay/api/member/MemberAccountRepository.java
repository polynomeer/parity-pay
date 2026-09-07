package io.parity.pay.api.member;

import io.parity.pay.api.security.Role;
import io.parity.pay.shared.id.MemberId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 회원 계정 조회. */
@Repository
public class MemberAccountRepository {

    private static final RowMapper<MemberAccount> ROW_MAPPER = (rs, rowNum) -> new MemberAccount(
            MemberId.of(rs.getObject("member_id", UUID.class)),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("status"),
            parseRoles(rs.getString("roles")));

    private final JdbcTemplate jdbcTemplate;

    MemberAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MemberAccount> findByEmail(String email) {
        List<MemberAccount> rows = jdbcTemplate.query(
                "SELECT member_id, email, password_hash, status, roles FROM member WHERE email = ?", ROW_MAPPER, email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<MemberAccount> findById(MemberId memberId) {
        List<MemberAccount> rows = jdbcTemplate.query(
                "SELECT member_id, email, password_hash, status, roles FROM member WHERE member_id = ?",
                ROW_MAPPER,
                memberId.value());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void updateRoles(MemberId memberId, Set<Role> roles) {
        jdbcTemplate.update(
                "UPDATE member SET roles = ? WHERE member_id = ?",
                roles.stream().map(Role::name).collect(Collectors.joining(",")),
                memberId.value());
    }

    private static Set<Role> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return Set.of(Role.CUSTOMER);
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Role::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
