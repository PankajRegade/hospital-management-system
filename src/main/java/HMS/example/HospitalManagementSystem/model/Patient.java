package HMS.example.HospitalManagementSystem.model;

import jakarta.persistence.*;

@Entity
@Table(name = "patient")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String address;
    private int age;
    private String disease;
    private String email;
    private String gender;
    private String name;
    private String phone;

    // -------- Constructors --------
    public Patient() { }

    public Patient(String address, int age, String disease, String email,
                   String gender, String name, String phone) {
        this.address = address;
        this.age = age;
        this.disease = disease;
        this.email = email;
        this.gender = gender;
        this.name = name;
        this.phone = phone;
    }

    // --------- Getters & Setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getDisease() {
        return disease;
    }

    public void setDisease(String disease) {
        this.disease = disease;
    }

    public String getEmail() {
        return email;   // VERY IMPORTANT ← login matches this field
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getName() {
        return name;    // Used for fallback login search
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

}
