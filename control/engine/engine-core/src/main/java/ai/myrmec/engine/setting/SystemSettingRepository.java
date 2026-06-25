package ai.myrmec.engine.setting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * #71a &mdash; persistence for {@link SystemSetting} rows.
 */
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {

    List<SystemSetting> findAllByOrderByKeyAsc();
}
