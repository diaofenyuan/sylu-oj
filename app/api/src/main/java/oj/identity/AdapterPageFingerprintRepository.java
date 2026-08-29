package oj.identity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdapterPageFingerprintRepository extends JpaRepository<AdapterPageFingerprint, Long> {

    List<AdapterPageFingerprint> findAllByOrderByCapturedAtDesc(Pageable pageable);
}
