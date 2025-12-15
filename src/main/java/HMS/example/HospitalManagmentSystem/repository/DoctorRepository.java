package HMS.example.HospitalManagmentSystem.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import HMS.example.HospitalManagmentSystem.model.Doctor;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    Doctor findByEmail(String email);
}
