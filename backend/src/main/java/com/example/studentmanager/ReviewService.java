package com.example.studentmanager;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {
    private static final String IGNORE = "IGNORE", NEW = "NEW", UNDECIDED = "UNDECIDED";
    private static class MutableRow {
        final int number; final List<String> values; final Map<Integer, String> errors; boolean excluded;
        List<ReviewView.Issue> issues = new ArrayList<>(); List<Student> candidates = List.of();
        MutableRow(ImportGrid.Row row) { number = row.number(); values = new ArrayList<>(row.values()); errors = new HashMap<>(row.errors()); }
    }
    private static class Draft {
        final String id = UUID.randomUUID().toString(), owner;
        final Instant expiresAt = Instant.now().plusSeconds(3600);
        String mode, purpose = "IMPORT", parentId, stage = "MAPPING", message = "请确认字段对应关系", fingerprint, schemaVersion;
        int revision = 1; ImportGrid grid; byte[] bytes; String filename, originalText; Boolean hasHeader;
        List<MutableRow> rows; List<ColumnAdviser.Guess> guesses = new ArrayList<>();
        final Map<Integer, String> mappings = new LinkedHashMap<>();
        final Map<Integer, StudentField> additions = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>(); final List<ReviewView.Issue> issues = new ArrayList<>();
        final Map<String, Map<String, Object>> backfill = new LinkedHashMap<>();
        List<Student> existing = List.of(); List<BatchReport.Input> preparedInputs = List.of(); BatchReport prepared;
        volatile boolean mappingConfirmed, completed, schemaChanged; OperationResult result;
        Draft(String owner, String mode, ImportGrid grid) { this.owner = owner; this.mode = mode; this.grid = grid; rows = grid.rows().stream().map(MutableRow::new).toList(); notes.addAll(grid.notes()); }
    }
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final ImportReader reader;
    private final ColumnAdviser adviser;
    private final FieldCatalog catalog;
    private final StudentService students;
    private final BatchService batches;
    private final SchemaService schemas;
    public ReviewService(ImportReader reader, ColumnAdviser adviser, FieldCatalog catalog, StudentService students, BatchService batches, SchemaService schemas) { this.reader = reader; this.adviser = adviser; this.catalog = catalog; this.students = students; this.batches = batches; this.schemas = schemas; }
    public ReviewView text(String owner, String mode, String text, Boolean hasHeader) { Draft d = create(owner, mode, reader.text(text, hasHeader)); d.originalText = text; d.hasHeader = hasHeader; return view(d); }
    public ReviewView objects(String owner, String mode, JsonNode rows) { return view(create(owner, mode, reader.objects(rows))); }
    public ReviewView excel(String owner, String mode, byte[] bytes, String filename) { Draft d = create(owner, mode, reader.excel(bytes, filename, null, null, 500)); d.bytes = bytes.clone(); d.filename = filename; return view(d); }
    private Draft create(String owner, String mode, ImportGrid grid) {
        if (owner == null || owner.isBlank()) throw AppException.bad("请先创建会话");
        drafts.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(Instant.now()));
        if (drafts.size() >= 200) throw AppException.bad("待处理草稿较多，请先完成或取消已有草稿");
        Draft d = new Draft(owner, BatchService.mode(mode), grid); initialize(d); drafts.put(d.id, d); evaluate(d); return d;
    }
    private void initialize(Draft d) {
        d.guesses = new ArrayList<>(adviser.suggest(d.grid, fields(d)));
        d.mappings.clear(); d.additions.clear(); d.mappingConfirmed = false;
        for (int column = 0; column < d.grid.headers().size(); column++) {
            final int current = column;
            var candidates = d.guesses.stream().filter(guess -> guess.column() == current).map(ColumnAdviser.Guess::target).distinct().toList();
            d.mappings.put(column, candidates.size() == 1 ? candidates.get(0) : UNDECIDED);
        }
    }
    private Draft owned(String owner, String id) {
        Draft d = drafts.get(id);
        if (d == null || !d.owner.equals(owner)) throw new AppException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "纠错草稿不存在，请重新上传或输入");
        if (d.expiresAt.isBefore(Instant.now())) throw new AppException(HttpStatus.GONE, "REVIEW_EXPIRED", "纠错草稿已过期，请重新上传；未确认的数据不会执行");
        return d;
    }
    private List<StudentField> fields(Draft d) { return d.parentId == null ? catalog.fields() : new ArrayList<>(owned(d.owner, d.parentId).additions.values()); }
    public ReviewView get(String owner, String id) { Draft d = owned(owner, id); synchronized (d) { return view(d); } }
    public List<ReviewView.Ref> pending(String owner) {
        return drafts.values().stream().filter(d -> d.owner.equals(owner) && d.parentId == null && !d.completed && d.expiresAt.isAfter(Instant.now())).sorted(java.util.Comparator.comparing((Draft d) -> d.expiresAt).reversed()).map(d -> new ReviewView.Ref(d.id, "校对并继续处理", d.stage, d.issues.size(), d.schemaChanged)).toList();
    }
    public ReviewView cancel(String owner, String id, int expected) {
        Draft d = owned(owner, id); synchronized (d) {
            if (d.completed) return view(d); revision(d, expected); d.completed = true; d.stage = "CANCELLED"; d.message = d.schemaChanged ? "已取消未执行的学生操作；此前明确确认的新字段和全员补全值已保存，不会撤销" : "已取消本次草稿，未执行学生数据操作";
            d.result = OperationResult.success("review_cancel", "REVIEW_CANCELLED", d.message, null, false); d.revision++; return view(d);
        }
    }
    public boolean requiresMapping(String text) {
        String raw = text.strip();
        if (raw.startsWith("{") || raw.startsWith("[")) return true;
        if (java.util.regex.Pattern.compile("(?i)\\b(id|stuid|student_id|studentno|sno|name|studentname)\\s*[:：=]").matcher(raw).find()) return true;
        if (raw.contains("\n")) {
            var lines = raw.lines().filter(line -> !line.isBlank()).toList();
            String header = lines.get(0); if (lines.size() > 1 && header.matches(".*[：:]\\s*")) header = lines.get(1);
            var cells = header.strip().split("[\\s,，\\t]+");
            if (cells.length > 0 && ImportReader.alias(cells[0]) != null) {
                Set<String> canonical = new HashSet<>(List.of("学号", "姓名", "新学号")); catalog.fields().forEach(field -> canonical.add(field.label()));
                Set<String> seen = new HashSet<>(); for (String cell : cells) if (!canonical.contains(cell) || !seen.add(cell)) return true;
                try {
                    ImportGrid grid = reader.text(raw, true);
                    for (var row : grid.rows()) for (int column = 0; column < grid.headers().size(); column++) {
                        String label = grid.headers().get(column), value = column < row.values().size() ? row.values().get(column) : "";
                        String core = ImportReader.alias(label);
                        if ("studentNo".equals(core) || "newStudentNo".equals(core)) StudentService.validate(value, "学号", 7);
                        else if ("name".equals(core)) StudentService.validate(value, "姓名", 15);
                        else for (StudentField field : catalog.fields()) if (field.label().equals(label)) field.normalize(value);
                    }
                } catch (AppException ex) { return true; }
            }
        }
        return false;
    }
    private void revision(Draft d, int expected) { if (expected != d.revision) throw new AppException(HttpStatus.CONFLICT, "REVIEW_CHANGED", "草稿已在其他窗口更新，请刷新后再提交"); if (d.completed) throw AppException.bad("本草稿已经完成，请新建草稿继续操作"); }
    public ReviewView patch(String owner, String id, ReviewView.Patch patch) {
        Draft d = owned(owner, id); synchronized (d) {
            revision(d, patch.revision());
            if (patch.mode() != null && !patch.mode().equals(d.mode)) { d.mode = BatchService.mode(patch.mode()); d.notes.add("用户将操作类型改为" + d.mode); }
            if (patch.mappings() != null) {
                Map<Integer, String> mapping = new LinkedHashMap<>(); Map<Integer, StudentField> additions = new LinkedHashMap<>();
                for (ReviewView.Mapping choice : patch.mappings()) {
                    if (choice.target() == null) throw AppException.bad("请选择每列的处理方式");
                    if (choice.column() < 0 || choice.column() >= d.grid.headers().size() || mapping.put(choice.column(), choice.target()) != null) throw AppException.bad("字段映射索引无效或重复");
                    if (NEW.equals(choice.target())) {
                        if (d.parentId != null) throw AppException.bad("补全已有学生时只能映射待补全字段，不能在补全文件中新建其他字段");
                        StudentField previous = d.additions.get(choice.column());
                        String key = previous == null ? "f_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : previous.key();
                        additions.put(choice.column(), new StudentField(key, StudentField.label(choice.label()), StudentField.type(choice.type()), true));
                    }
                }
                if (mapping.size() != d.grid.headers().size()) throw AppException.bad("请为每一列选择对应字段、新增或明确忽略");
                d.mappings.clear(); d.mappings.putAll(mapping); d.additions.clear(); d.additions.putAll(additions); d.mappingConfirmed = false; d.fingerprint = null;
            }
            if (patch.addColumn() != null) addColumn(d, patch.addColumn());
            if (patch.cells() != null) for (ReviewView.CellEdit edit : patch.cells()) {
                MutableRow row = d.rows.stream().filter(value -> value.number == edit.row()).findFirst().orElseThrow(() -> AppException.bad("需要编辑的行不存在"));
                if (edit.column() < 0 || edit.column() >= d.grid.headers().size() || edit.value() == null || edit.value().length() > 2000) throw AppException.bad("单元格编辑参数无效");
                while (row.values.size() <= edit.column()) row.values.add(""); row.values.set(edit.column(), edit.value()); row.errors.remove(edit.column());
            }
            if (patch.excluded() != null) { Set<Integer> excluded = new HashSet<>(patch.excluded()); if (excluded.stream().anyMatch(number -> d.rows.stream().noneMatch(row -> row.number == number))) throw AppException.bad("排除的行不存在"); if (!new HashSet<>(d.rows.stream().filter(row -> row.excluded).map(row -> row.number).toList()).equals(excluded)) d.notes.add("用户明确选择排除" + excluded.size() + "行，其余记录继续校验"); d.rows.forEach(row -> row.excluded = excluded.contains(row.number)); }
            if (patch.cells() != null && !patch.cells().isEmpty()) d.notes.add("按明确选择修正" + patch.cells().size() + "个单元格，重新进行完整校验");
            if (patch.backfill() != null) for (var row : patch.backfill()) {
                if (d.existing.stream().noneMatch(student -> student.studentNo().equals(row.studentNo()))) throw AppException.bad("补全名单已变化，请刷新已有学生名单");
                Map<String, Object> values = d.backfill.computeIfAbsent(row.studentNo(), ignored -> new LinkedHashMap<>());
                if (row.values() != null) for (var value : row.values().entrySet()) { if (d.additions.values().stream().noneMatch(field -> field.key().equals(value.getKey()))) throw AppException.bad("补全值包含未知字段"); values.put(value.getKey(), value.getValue()); }
            }
            if (Boolean.TRUE.equals(patch.confirmMapping())) { d.mappingConfirmed = true; d.notes.add("用户确认字段映射及忽略列选择"); }
            if ("REFRESH_BACKFILL".equals(patch.action())) { d.fingerprint = null; d.notes.add("用户刷新已有学生名单，原已填补全值按学号保留"); }
            if ("KEEP_FIRST_DUPLICATE".equals(patch.action())) { Set<String> seen = new HashSet<>(); for (MutableRow row : d.rows) if (!row.excluded && !seen.add(value(d, row, "studentNo"))) row.excluded = true; d.notes.add("用户选择对重复学号仅保留第一行"); }
            d.revision++; evaluate(d); return view(d);
        }
    }
    private void addColumn(Draft d, String target) {
        String label = switch (target) { case "studentNo" -> "学号"; case "name" -> "姓名"; case "newStudentNo" -> "新学号"; default -> fields(d).stream().filter(field -> field.key().equals(target)).findFirst().orElseThrow(() -> AppException.bad("未知补全字段")).label(); };
        if (d.mappings.containsValue(target)) throw AppException.bad("已存在该字段列，请编辑现有列");
        List<String> headers = new ArrayList<>(d.grid.headers()); headers.add(label);
        d.grid = new ImportGrid(d.grid.source(), d.grid.sheetIndex(), d.grid.headerRow(), d.grid.sheets(), headers, d.grid.rows(), d.grid.notes());
        d.mappings.put(headers.size() - 1, target); d.rows.forEach(row -> { while (row.values.size() < headers.size()) row.values.add(""); });
        d.mappingConfirmed = false; d.notes.add("用户添加“" + label + "”空列，等待逐行补全");
    }
    public ReviewView source(String owner, String id, int revision, Integer sheet, Integer header, Boolean hasHeader) {
        Draft d = owned(owner, id); synchronized (d) { revision(d, revision);
            if (d.bytes != null) d.grid = reader.excel(d.bytes, d.filename, sheet, header, d.parentId == null ? 500 : 10000);
            else if (d.originalText != null) { d.hasHeader = hasHeader; d.grid = reader.text(d.originalText, hasHeader); }
            else throw AppException.bad("当前数据没有可重新选择的原始文件");
            d.rows = d.grid.rows().stream().map(MutableRow::new).toList(); initialize(d); d.fingerprint = null; d.revision++; d.notes.add("用户重新选择数据源，编辑值已按原始文件重新载入"); evaluate(d); return view(d);
        }
    }
    public ReviewView suggest(String owner, String id, int revision) {
        Draft d = owned(owner, id); synchronized (d) { revision(d, revision);
            List<ColumnAdviser.Guess> model = adviser.model(d.grid, fields(d));
            d.guesses.addAll(model); d.notes.add(model.isEmpty() ? "暂未获得可用模型建议，请手动确认字段" : "DeepSeek提出" + model.size() + "条字段匹配建议，尚未替用户确认"); d.revision++; return view(d);
        }
    }
    private ReviewView.Issue issue(String code, String message, Integer row, Integer column, String suggestion, String... choices) { return new ReviewView.Issue(code, message, row, column, suggestion, List.of(choices)); }
    private void problem(Draft d, MutableRow row, String code, String message, String target, String suggestion, String... choices) {
        Integer column = d.mappings.entrySet().stream().filter(entry -> target.equals(resolve(d, entry.getKey()))).map(Map.Entry::getKey).findFirst().orElse(null);
        var issue = issue(code, message, row.number, column, suggestion, choices); row.issues.add(issue); d.issues.add(issue);
    }
    private String resolve(Draft d, int column) { return NEW.equals(d.mappings.get(column)) && d.additions.containsKey(column) ? d.additions.get(column).key() : d.mappings.get(column); }
    private String value(Draft d, MutableRow row, String target) {
        for (var entry : d.mappings.entrySet()) if (target.equals(resolve(d, entry.getKey()))) return row.values.size() > entry.getKey() ? row.values.get(entry.getKey()) : "";
        return "";
    }
    private boolean mapped(Draft d, String target) { return d.mappings.keySet().stream().anyMatch(index -> target.equals(resolve(d, index))); }
    private void evaluate(Draft d) {
        d.issues.clear(); d.rows.forEach(row -> { row.issues = new ArrayList<>(); row.candidates = List.of(); });
        if (d.completed) { d.stage = "COMPLETE"; return; }
        Set<String> targets = new HashSet<>(), labels = new HashSet<>(List.of("学号", "姓名")); List<StudentField> definitions = fields(d);
        definitions.forEach(field -> labels.add(ColumnAdviser.normalize(field.label())));
        Set<String> allowed = new HashSet<>(List.of("studentNo", "name", "newStudentNo")); definitions.forEach(field -> allowed.add(field.key()));
        for (int column = 0; column < d.grid.headers().size(); column++) {
            String target = d.mappings.getOrDefault(column, UNDECIDED);
            if (target.equals(UNDECIDED)) d.issues.add(issue("UNDECIDED_COLUMN", "请确定“" + columnLabel(d, column) + "”是现有字段、新字段还是忽略", null, column, null));
            else if (!target.equals(IGNORE)) {
                if (!target.equals(NEW) && !allowed.contains(target)) d.issues.add(issue("UNKNOWN_MAPPING", "对应字段已变化，请重新选择", null, column, null));
                if (!target.equals(NEW) && !targets.add(target)) d.issues.add(issue("DUPLICATE_MAPPING", "多个列都对应同一字段，请保留准确的一列，将其余列明确设为忽略或其他字段", null, column, null));
                if (target.equals(NEW)) { StudentField field = d.additions.get(column); if (field == null || ImportReader.alias(field.label()) != null || !labels.add(ColumnAdviser.normalize(field.label()))) d.issues.add(issue("DUPLICATE_FIELD", "新字段名称与已有字段或其他新字段含义重复，请重新映射或改名", null, column, null)); }
            }
        }
        if (!mapped(d, "studentNo")) d.issues.add(issue("MISSING_COLUMN", "缺少学号列，可添加空列逐行补全，或改选原始数据列", null, null, "studentNo"));
        if (d.parentId == null && Set.of("CREATE", "UPSERT").contains(d.mode) && !mapped(d, "name")) d.issues.add(issue("MISSING_COLUMN", "缺少姓名列，请添加空列补全或选择对应列", null, null, "name"));
        if (d.parentId != null && definitions.stream().noneMatch(field -> mapped(d, field.key()))) d.issues.add(issue("MISSING_COLUMN", "请至少选择一个待补全的新字段", null, null, null));
        if (!d.issues.isEmpty() || !d.mappingConfirmed) { d.stage = "MAPPING"; d.message = "请逐列确认映射，重复和额外列不能自动选择或忽略"; return; }
        List<Student> current = students.all(); Map<String, Student> currentById = new HashMap<>(); current.forEach(student -> currentById.put(student.studentNo(), student));
        Map<String, Integer> frequency = new HashMap<>(); for (MutableRow row : d.rows) if (!row.excluded) frequency.merge(value(d, row, "studentNo").strip(), 1, Integer::sum);
        List<BatchReport.Input> inputs = new ArrayList<>(); List<StudentField> additions = new ArrayList<>(d.additions.values());
        for (MutableRow row : d.rows) {
            if (row.excluded) continue;
            String id = value(d, row, "studentNo"), name = value(d, row, "name"); Student before = currentById.get(id.strip());
            for (var error : row.errors.entrySet()) if (!IGNORE.equals(d.mappings.get(error.getKey()))) { var issue = issue("SOURCE_CELL_ERROR", error.getValue(), row.number, error.getKey(), null); row.issues.add(issue); d.issues.add(issue); }
            try { StudentService.validate(id, "学号", 7); } catch (AppException ex) { problem(d, row, "INVALID_STUDENT_NO", ex.getMessage(), "studentNo", cleanId(id)); }
            if (!id.isBlank() && !id.equals(id.strip())) problem(d, row, "FORMAT_SUGGESTION", "学号首尾有空格，是否移除？", "studentNo", id.strip());
            if (!id.isBlank() && frequency.getOrDefault(id.strip(), 0) > 1) problem(d, row, "DUPLICATE_ID", "本批有相同学号，请选择保留记录或排除重复行", "studentNo", null);
            if (id.isBlank() || before == null) row.candidates = current.stream().filter(student -> !name.isBlank() && student.name().equals(name.strip()) || !id.isBlank() && student.studentNo().equalsIgnoreCase(cleanId(id))).limit(10).toList();
            boolean creating = d.mode.equals("CREATE") || d.mode.equals("UPSERT") && before == null;
            if (d.parentId != null) {
                Draft parent = owned(d.owner, d.parentId);
                if (parent.existing.stream().noneMatch(student -> student.studentNo().equals(id.strip()))) problem(d, row, "BACKFILL_UNKNOWN_ID", "该学号不在待补全名单中，请选择正确学号或排除此行", "studentNo", null);
            } else {
                if (d.mode.equals("CREATE") && before != null) problem(d, row, "STUDENT_EXISTS", "学号已存在：是想更新已有学生吗？可切换为更新/新增或更新，或修改学号、排除此行", "studentNo", null, "UPDATE", "UPSERT");
                if (Set.of("UPDATE", "DELETE", "QUERY").contains(d.mode) && before == null) problem(d, row, "STUDENT_NOT_FOUND", "找不到该学号：请核对或选择候选；若想新增，请明确切换操作类型", "studentNo", null, "CREATE", "UPSERT");
                if (creating || mapped(d, "name") && !Set.of("DELETE", "QUERY").contains(d.mode)) try { StudentService.validate(name, "姓名", 15); } catch (AppException ex) { problem(d, row, "INVALID_NAME", ex.getMessage(), "name", null); }
                if (mapped(d, "name") && !name.equals(name.strip())) problem(d, row, "FORMAT_SUGGESTION", "姓名首尾有空格，是否移除？", "name", name.strip());
                if (d.mode.equals("DELETE") && before != null && !name.isBlank() && !name.strip().equals(before.name())) problem(d, row, "NAME_MISMATCH", "姓名与当前学号不一致，请核对目标学生后修改或排除", "name", null);
            }
            Map<String, Object> values = new LinkedHashMap<>(); List<StudentField> expected = new ArrayList<>(definitions); expected.addAll(additions);
            if (!Set.of("DELETE", "QUERY").contains(d.mode) || d.parentId != null) for (StudentField field : expected) {
                if (!mapped(d, field.key())) {
                    if (creating && d.parentId == null && field.required()) problem(d, row, "MISSING_FIELD", "新增学生缺少“" + field.label() + "”，请添加对应列并补全", field.key(), field.key());
                    continue;
                }
                String raw = value(d, row, field.key());
                try { values.put(field.key(), field.normalize(raw)); }
                catch (AppException ex) { problem(d, row, "FIELD_TYPE", ex.getMessage(), field.key(), conversion(field, raw)); }
            }
            String newId = mapped(d, "newStudentNo") ? value(d, row, "newStudentNo") : null;
            inputs.add(new BatchReport.Input(row.number, id, mapped(d, "name") ? name : null, newId == null || newId.isBlank() ? null : newId, null, values));
        }
        d.preparedInputs = inputs;
        if (inputs.isEmpty()) d.issues.add(issue("NO_ROWS", "所有行都被排除了，请恢复至少一条记录或取消操作", null, null, null));
        if (!d.issues.isEmpty()) { d.stage = "ROWS"; d.message = "请在表格中修正、选择候选或明确排除问题行，再重新校验"; return; }
        if (d.parentId != null) { d.stage = "READY"; d.message = "补全文件已校验，可将值应用到已有学生补全表；这一步不会写入数据库"; return; }
        if (!additions.isEmpty()) {
            if (d.fingerprint == null) {
                d.existing = List.copyOf(current); d.fingerprint = schemas.fingerprint();
                for (Student student : current) {
                    Map<String, Object> values = d.backfill.computeIfAbsent(student.studentNo(), ignored -> new LinkedHashMap<>());
                    inputs.stream().filter(input -> input.studentNo().strip().equals(student.studentNo())).findFirst().ifPresent(input -> { for (StudentField field : additions) if (input.fields().containsKey(field.key())) values.putIfAbsent(field.key(), input.fields().get(field.key())); });
                }
                d.backfill.keySet().removeIf(id -> current.stream().noneMatch(student -> student.studentNo().equals(id)));
            }
            if (!schemas.fingerprint().equals(d.fingerprint)) d.issues.add(issue("DATASET_CHANGED", "已有学生或字段发生变化，请刷新补全名单；原已填值会按学号保留", null, null, null));
            for (Student student : d.existing) for (StudentField field : additions) {
                Object raw = d.backfill.getOrDefault(student.studentNo(), Map.of()).get(field.key());
                try { field.normalize(raw); } catch (AppException ex) { d.issues.add(issue("BACKFILL_INVALID", "学号" + student.studentNo() + "：" + ex.getMessage(), null, null, null, student.studentNo(), field.key())); }
            }
            d.stage = d.issues.isEmpty() ? "SCHEMA_READY" : "BACKFILL"; d.message = "新增字段前，必须补齐并校验全部" + d.existing.size() + "名已有学生的新字段值"; return;
        }
        BatchReport preview = batches.preview(d.mode, inputs); d.prepared = preview; d.schemaVersion = catalog.version();
        for (BatchReport.Row row : preview.rows()) if (row.status().equals("INVALID")) {
            MutableRow source = d.rows.stream().filter(value -> value.number == row.row()).findFirst().orElseThrow(); problem(d, source, row.code(), row.message(), "studentNo", null);
        }
        d.stage = d.issues.isEmpty() ? "READY" : "ROWS"; d.message = d.issues.isEmpty() ? "已校验全部有效记录，请核对最终预览后明确确认执行" : "还存在未解决的问题，请修正或排除后再次校验";
    }
    private String cleanId(String id) { return java.text.Normalizer.normalize(id == null ? "" : id, java.text.Normalizer.Form.NFKC).replaceAll("[\\u200B-\\u200D\\uFEFF]", "").strip(); }
    private String conversion(StudentField field, String raw) {
        if (field.gender()) { String value = raw.strip().toLowerCase(java.util.Locale.ROOT); if (Set.of("male", "m", "男性").contains(value)) return "男"; if (Set.of("female", "f", "女性").contains(value)) return "女"; return null; }
        String value = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC).strip();
        if (field.type().equals("BOOLEAN")) { if (Set.of("是", "yes", "Y", "1", "YES").contains(value)) return "true"; if (Set.of("否", "no", "N", "0", "NO").contains(value)) return "false"; }
        if (field.type().equals("DATE")) for (String format : List.of("uuuu/M/d", "uuuu年M月d日", "uuuuMMdd")) try { return java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ofPattern(format).withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString(); } catch (Exception ignored) {}
        if (field.type().equals("DECIMAL") || field.type().equals("INTEGER")) try { String number = value.replace(",", ""); field.normalize(number); return number; } catch (Exception ignored) {}
        return !value.equals(raw) ? value : null;
    }
    public ReviewView addFields(String owner, String id, int expected) {
        Draft d = owned(owner, id); synchronized (d) { revision(d, expected); if (!d.stage.equals("SCHEMA_READY")) throw AppException.bad("请先修正数据并补齐所有已有学生的新字段值");
            var change = schemas.add(new ArrayList<>(d.additions.values()), d.fingerprint, d.backfill);
            d.schemaChanged = true;
            for (var entry : d.additions.entrySet()) d.mappings.put(entry.getKey(), entry.getValue().key());
            d.notes.add("已按用户确认新增" + d.additions.size() + "个正式字段，并补齐" + change.backfilled() + "名已有学生；升级前数据备份已保留");
            d.additions.clear(); d.existing = List.of(); d.backfill.clear(); d.fingerprint = null; d.revision++; evaluate(d); return view(d);
        }
    }
    public ReviewView finish(String owner, String id, int expected) {
        Draft d = owned(owner, id); synchronized (d) {
            if (d.completed) return d.parentId == null ? view(d) : get(owner, d.parentId);
            revision(d, expected); if (!d.stage.equals("READY")) throw AppException.bad("还有待确认或未修正的问题，不能执行");
            if (d.parentId != null) {
                Draft parent = owned(owner, d.parentId); synchronized (parent) {
                    if (parent.completed || parent.additions.isEmpty()) throw new AppException(HttpStatus.CONFLICT, "BACKFILL_PARENT_CHANGED", "原补全任务已完成或取消，请返回主任务查看最新状态");
                    Set<String> pendingFields = parent.additions.values().stream().map(StudentField::key).collect(java.util.stream.Collectors.toSet());
                    for (var input : d.preparedInputs) {
                        if (parent.existing.stream().noneMatch(student -> student.studentNo().equals(input.studentNo().strip())) || !pendingFields.containsAll(input.fields().keySet()))
                            throw new AppException(HttpStatus.CONFLICT, "BACKFILL_PARENT_CHANGED", "待补全名单或字段已变化，请重新上传补全文件");
                    }
                    for (var input : d.preparedInputs) parent.backfill.computeIfAbsent(input.studentNo().strip(), ignored -> new LinkedHashMap<>()).putAll(input.fields());
                    d.completed = true; d.stage = "COMPLETE"; parent.revision++; parent.notes.add("用户确认从补全文件合并" + d.preparedInputs.size() + "条已有学生数据，尚未写入数据库"); evaluate(parent); return view(parent);
                }
            }
            if (!catalog.version().equals(d.schemaVersion)) throw new AppException(HttpStatus.CONFLICT, "SCHEMA_CHANGED", "字段已变化，请重新校验并补全新字段后再确认");
            d.result = d.mode.equals("QUERY") ? batches.result("review_query", d.prepared) : batches.execute("review_" + d.mode.toLowerCase(java.util.Locale.ROOT), d.prepared);
            d.completed = true; d.stage = "COMPLETE"; d.message = d.result.message(); d.revision++; d.notes.add(d.result.message()); return view(d);
        }
    }
    public ReviewView backfillFile(String owner, String parentId, byte[] bytes, String filename) {
        Draft parent = owned(owner, parentId); synchronized (parent) {
            if (parent.completed || parent.additions.isEmpty()) throw AppException.bad("当前没有待补全的新字段，或主任务已经结束");
            Draft child = create(owner, "UPDATE", reader.excel(bytes, filename, null, null, 10000)); child.parentId = parentId; child.purpose = "BACKFILL"; child.bytes = bytes.clone(); child.filename = filename; initialize(child); evaluate(child); return view(child);
        }
    }
    public OperationResult reference(ReviewView view) { boolean changed = ownedFromView(view).schemaChanged; return new OperationResult("review_student_data", true, "REVIEW_REQUIRED", (changed ? "新字段和已有学生补全值已保存。" : "") + view.message(), new ReviewView.Ref(view.id(), "校对并继续处理", view.stage(), view.issues().size(), changed), null, changed); }
    private Draft ownedFromView(ReviewView view) { Draft draft = drafts.get(view.id()); if (draft == null) throw AppException.bad("草稿不存在"); return draft; }
    public ReviewView retry(String owner, BatchReport report) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var rows = mapper.createArrayNode();
        for (BatchReport.Row row : report.rows()) if (Set.of("FAILED", "INVALID").contains(row.status())) {
            var object = rows.addObject(); object.put("学号", row.studentNo()); object.put("姓名", row.name()); if (row.newStudentNo() != null) object.put("新学号", row.newStudentNo());
            Student values = row.after() == null ? row.before() : row.after(); if (values != null) for (StudentField field : catalog.fields()) { Object value = values.fields().get(field.key()); object.put(field.label(), value == null ? "" : value.toString()); }
        }
        if (rows.isEmpty()) throw AppException.bad("没有失败记录需要修正");
        ReviewView view = objects(owner, report.mode(), rows); Draft d = owned(owner, view.id()); d.notes.add("仅载入上一批失败记录；已成功或跳过的记录不会重复执行"); return view(d);
    }
    private String columnLabel(Draft d, int column) { String label = d.grid.headers().get(column); return label == null || label.isBlank() ? "未命名列" + (column + 1) : label; }
    private ReviewView view(Draft d) {
        List<ReviewView.Column> columns = new ArrayList<>();
        for (int i = 0; i < d.grid.headers().size(); i++) { final int current = i; StudentField added = d.additions.get(i);
            columns.add(new ReviewView.Column(i, columnLabel(d, i), d.mappings.getOrDefault(i, UNDECIDED), added == null ? columnLabel(d, i) : added.label(), added == null ? infer(d, i) : added.type(), d.rows.stream().map(row -> row.values.size() > current ? row.values.get(current) : "").filter(value -> !value.isBlank()).distinct().limit(3).toList(), d.guesses.stream().filter(guess -> guess.column() == current).toList())); }
        List<ReviewView.Row> rows = d.rows.stream().map(row -> { Map<Integer, String> values = new LinkedHashMap<>(); for (int i = 0; i < d.grid.headers().size(); i++) values.put(i, row.values.size() > i ? row.values.get(i) : ""); return new ReviewView.Row(row.number, values, row.excluded, List.copyOf(row.issues), row.candidates); }).toList();
        return new ReviewView(d.id, d.revision, d.purpose, d.parentId, d.mode, d.stage, d.message, d.expiresAt, d.grid.source(), d.grid.sheetIndex(), d.grid.headerRow(), d.grid.sheets(), columns, rows, List.copyOf(d.issues), fields(d), new ArrayList<>(d.additions.values()), d.existing, copy(d.backfill), List.copyOf(d.notes), d.prepared, d.result);
    }
    private Map<String, Map<String, Object>> copy(Map<String, Map<String, Object>> source) { Map<String, Map<String, Object>> result = new LinkedHashMap<>(); source.forEach((key, value) -> result.put(key, new LinkedHashMap<>(value))); return result; }
    private String infer(Draft d, int column) {
        String label = columnLabel(d, column).toLowerCase(java.util.Locale.ROOT);
        if (label.matches(".*(编号|代码|电话|手机|邮编|id|phone|code).*$")) return "TEXT";
        List<String> values = d.rows.stream().map(row -> row.values.size() > column ? row.values.get(column) : "").filter(value -> !value.isBlank()).limit(20).toList();
        if (values.isEmpty()) return "TEXT";
        for (String type : List.of("BOOLEAN", "INTEGER", "DECIMAL", "DATE")) try { StudentField field = new StudentField("preview", label, type, true); for (String value : values) field.normalize(value); return type; } catch (Exception ignored) {}
        return "TEXT";
    }
}
