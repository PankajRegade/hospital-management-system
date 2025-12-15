package HMS.example.HospitalManagementSystem.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import HMS.example.HospitalManagementSystem.model.Doctor;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    Doctor findByEmail(String email);
}
