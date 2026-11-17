(() => {
  "use strict";

  const state = {
    overview: null,
    history: null,
    activeType: null,
    sortBy: "_id",
    sortDir: "asc",
    offset: 0,
    limit: 100,
    globalFilter: "",
    colFilters: {},
    selectedEntityId: null,
    datasheet: null,
    branch: "",
    asOf: "",
    catalogFilter: ""
  };

  const el = (tag, props = {}, children = []) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === "className") node.className = v;
      else if (k === "textContent") node.textContent = v;
      else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2).toLowerCase(), v);
      else if (v !== undefined && v !== null) node.setAttribute(k, v);
    }
    for (const child of Array.isArray(children) ? children : [children]) {
      if (typeof child === "string") node.appendChild(document.createTextNode(child));
      else if (child) node.appendChild(child);
    }
    return node;
  };

  const toast = (message, type = "ok") => {
    const area = document.getElementById("toastArea");
    const node = el("div", { className: `toast ${type}`, textContent: message });
    area.appendChild(node);
    setTimeout(() => node.remove(), 4200);
  };

  const apiGet = async (path, params = {}) => {
    const url = new URL(path, window.location.origin);
    if (state.branch) url.searchParams.set("branch", state.branch);
    if (state.asOf) url.searchParams.set("asOf", state.asOf);
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, v);
    }
    const resp = await fetch(url);
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.message || `HTTP ${resp.status}`);
    return data;
  };

  const apiPost = async (path, body = {}) => {
    const actor = document.getElementById("actorInput").value.trim() || "studio-user";
    const resp = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ branch: state.branch, actor, ...body })
    });
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.message || `HTTP ${resp.status}`);
    return data;
  };

  const debounce = (fn, ms = 220) => {
    let t;
    return (...args) => {
      clearTimeout(t);
      t = setTimeout(() => fn(...args), ms);
    };
  };

  const switchView = (viewName) => {
    document.querySelectorAll(".tab-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.view === viewName);
    });
    document.querySelectorAll(".view-panel").forEach((p) => {
      p.classList.toggle("active", p.id === `view-${viewName}`);
    });
    if (viewName === "schema") renderSchemaView();
    if (viewName === "history") loadHistory();
  };

  const loadAll = async () => {
    try {
      const [overview, history] = await Promise.all([apiGet("/api/overview"), apiGet("/api/history")]);
      state.overview = overview;
      state.history = history;
      if (!state.branch) state.branch = overview.activeBranch;
      renderTopContext();
      renderCatalog();
      renderSchemaSelectors();
      renderQueryPresets();

      if (!state.activeType && overview.objectTypes.length > 0) {
        const preferred = overview.objectTypes.find((t) => t.name === "Invariant")
          || overview.objectTypes.find((t) => t.count > 0)
          || overview.objectTypes[0];
        state.activeType = preferred.name;
      }
      if (state.activeType) {
        await loadDatasheet();
      }
      if (state.selectedEntityId) {
        await loadEntityDetail(state.selectedEntityId, true);
      }
      if (document.getElementById("view-schema").classList.contains("active")) {
        renderSchemaView();
      }
      if (document.getElementById("view-history").classList.contains("active")) {
        renderHistoryView();
      }
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const renderTopContext = () => {
    const ov = state.overview;
    document.getElementById("dbStatsLine").textContent =
      `${ov.entityCount} Entities · ${ov.edgeCount} Edges · Branch ${ov.activeBranch} · Commit ${ov.commitId}`;

    const branchSel = document.getElementById("branchSelect");
    branchSel.innerHTML = "";
    ov.branches.forEach((b) => {
      const opt = el("option", { value: b.name, textContent: `${b.name} (${b.headCommitId})` });
      if (b.name === ov.activeBranch) opt.selected = true;
      branchSel.appendChild(opt);
    });

    const asOfSel = document.getElementById("asOfSelect");
    asOfSel.innerHTML = "";
    asOfSel.appendChild(el("option", { value: "", textContent: `HEAD Live (${ov.headCommitId})` }));
    if (state.history) {
      [...state.history.commits].reverse().forEach((c) => {
        const label = `${c.id} · ${c.actor} · ${c.reason}`.slice(0, 62);
        const opt = el("option", { value: c.id, textContent: label });
        if (state.asOf === c.id) opt.selected = true;
        asOfSel.appendChild(opt);
      });
    }

    const banner = document.getElementById("historicalBanner");
    banner.classList.toggle("hidden", !ov.historical);
    if (ov.historical) {
      document.getElementById("historicalCommitLabel").textContent = ov.commitId;
    }
    document.getElementById("newRecordBtn").disabled = ov.historical;
  };

  const renderCatalog = () => {
    const ov = state.overview;
    const filter = state.catalogFilter.toLowerCase();

    const objUl = document.getElementById("objectTypesList");
    objUl.innerHTML = "";
    ov.objectTypes
      .filter((t) => !filter || t.name.toLowerCase().includes(filter))
      .forEach((t) => {
        const item = el("li", {
          className: `catalog-item ${state.activeType === t.name ? "active" : ""}`,
          onClick: () => selectType(t.name)
        }, [
          el("span", { className: "name", textContent: t.name }),
          el("span", { className: "badge", textContent: String(t.count) })
        ]);
        objUl.appendChild(item);
      });

    const valUl = document.getElementById("valueTypesList");
    valUl.innerHTML = "";
    ov.valueTypes
      .filter((t) => !filter || t.name.toLowerCase().includes(filter))
      .forEach((t) => {
        const item = el("li", {
          className: `catalog-item ${state.activeType === t.name ? "active" : ""}`,
          onClick: () => selectType(t.name)
        }, [
          el("span", { className: "name", textContent: `${t.name} (${t.scalarType})` }),
          el("span", { className: "badge", textContent: String(t.count) })
        ]);
        valUl.appendChild(item);
      });

    const predUl = document.getElementById("predicatesList");
    predUl.innerHTML = "";
    ov.predicates
      .filter((p) => !filter || p.name.toLowerCase().includes(filter))
      .forEach((p) => {
        predUl.appendChild(el("li", { className: "catalog-item" }, [
          el("span", { className: "name", textContent: `${p.name} (${p.count})` }),
          el("span", { className: "pred-sig", textContent: `${p.domainName} → ${p.rangeName}` })
        ]));
      });
  };

  const selectType = async (typeName) => {
    state.activeType = typeName;
    state.offset = 0;
    state.sortBy = "_id";
    state.sortDir = "asc";
    state.colFilters = {};
    state.globalFilter = "";
    document.getElementById("globalSearchInput").value = "";
    renderCatalog();
    switchView("datasheet");
    await loadDatasheet();
  };

  const loadDatasheet = async () => {
    if (!state.activeType) return;
    const params = {
      type: state.activeType,
      sortBy: state.sortBy,
      sortDir: state.sortDir,
      limit: state.limit,
      offset: state.offset,
      q: state.globalFilter
    };
    for (const [k, v] of Object.entries(state.colFilters)) {
      if (v) params[`filter.${k}`] = v;
    }
    const ds = await apiGet("/api/datasheet", params);
    state.datasheet = ds;
    renderDatasheet();
  };

  const renderDatasheet = () => {
    const ds = state.datasheet;
    document.getElementById("activeTypeTitle").textContent = ds.typeName;
    document.getElementById("activeTypeMeta").textContent =
      `${ds.typeKind === "VALUE_TYPE" ? "Werttyp" : "Objekttyp"} · ${ds.filteredCount} / ${ds.totalCount} Datensätze`;

    const thead = document.getElementById("datasheetHead");
    const sig = `${ds.typeName}|${ds.columns.map((c) => c.name).join(",")}`;

    if (thead.dataset.sig !== sig) {
      thead.dataset.sig = sig;
      thead.innerHTML = "";
      const headerRow = el("tr");
      const filterRow = el("tr", { className: "filter-row" });

      const addHeader = (key, label, subtitle) => {
        const labelSpan = el("span", { "data-sort-label": key, textContent: label });
        const th = el("th", {
          className: "th-sort",
          onClick: () => {
            if (state.sortBy === key) state.sortDir = state.sortDir === "asc" ? "desc" : "asc";
            else { state.sortBy = key; state.sortDir = "asc"; }
            loadDatasheet();
          }
        }, [
          labelSpan,
          subtitle ? el("span", { className: "col-kind-badge", textContent: subtitle }) : null
        ]);
        headerRow.appendChild(th);

        const input = el("input", {
          className: "filter-input",
          "data-filter-col": key,
          placeholder: "Filter...",
          value: state.colFilters[key] || "",
          onInput: debounce((ev) => {
            state.colFilters[key] = ev.target.value;
            state.offset = 0;
            loadDatasheet();
          }, 240)
        });
        filterRow.appendChild(el("th", {}, input));
      };

      addHeader("_id", "ID", "EntityId");
      ds.columns.forEach((col) => {
        const sub = col.kind === "ATTRIBUTE" ? (col.scalarType || col.rangeType) : `→ ${col.rangeType}`;
        addHeader(col.name, col.name, sub);
      });

      thead.appendChild(headerRow);
      thead.appendChild(filterRow);
    }

    thead.querySelectorAll("[data-sort-label]").forEach((span) => {
      const key = span.getAttribute("data-sort-label");
      const base = key === "_id" ? "ID" : key;
      const arrow = state.sortBy === key ? (state.sortDir === "asc" ? " ▲" : " ▼") : "";
      span.textContent = base + arrow;
    });
    thead.querySelectorAll("[data-filter-col]").forEach((inp) => {
      const key = inp.getAttribute("data-filter-col");
      const wanted = state.colFilters[key] || "";
      if (document.activeElement !== inp && inp.value !== wanted) {
        inp.value = wanted;
      }
    });

    const tbody = document.getElementById("datasheetBody");
    tbody.innerHTML = "";

    if (ds.rows.length === 0) {
      tbody.appendChild(el("tr", {}, el("td", {
        colSpan: ds.columns.length + 1,
        className: "muted",
        textContent: "Keine passenden Datensätze gefunden."
      })));
    }

    ds.rows.forEach((row) => {
      const tr = el("tr", {
        className: state.selectedEntityId === row._id ? "selected" : "",
        onClick: () => selectRow(row._id)
      });
      tr.appendChild(el("td", { className: "cell-id", textContent: `#${row._id}` }));

      ds.columns.forEach((col) => {
        const val = row._cells[col.name] ?? "";
        const td = el("td", {
          className: `cell-truncate ${col.editable ? "cell-editable" : ""}`,
          title: col.editable ? "Doppelklick zum Bearbeiten: " + val : val,
          textContent: val
        });
        if (col.editable) {
          td.addEventListener("dblclick", (ev) => {
            ev.stopPropagation();
            beginInlineEdit(td, row._id, col.name, val);
          });
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });

    const pag = document.getElementById("paginationBar");
    pag.innerHTML = "";
    const start = ds.filteredCount === 0 ? 0 : ds.offset + 1;
    const end = Math.min(ds.offset + ds.rows.length, ds.filteredCount);
    pag.appendChild(el("span", { textContent: `Zeige ${start}–${end} von ${ds.filteredCount} Treffern` }));

    const nav = el("div", { style: "display:flex;gap:6px;" }, [
      el("button", {
        className: "btn btn-sm btn-secondary",
        textContent: "Zurück",
        disabled: ds.offset <= 0 ? "true" : null,
        onClick: () => { state.offset = Math.max(0, state.offset - state.limit); loadDatasheet(); }
      }),
      el("button", {
        className: "btn btn-sm btn-secondary",
        textContent: "Weiter",
        disabled: ds.offset + ds.limit >= ds.filteredCount ? "true" : null,
        onClick: () => { state.offset += state.limit; loadDatasheet(); }
      })
    ]);
    pag.appendChild(nav);
  };

  const beginInlineEdit = (td, entityId, predicate, currentValue) => {
    if (state.overview?.historical) return;
    const input = el("input", { className: "inline-edit-input", value: currentValue });
    td.textContent = "";
    td.appendChild(input);
    input.focus();
    input.select();

    let committed = false;
    const finish = async (save) => {
      if (committed) return;
      committed = true;
      if (!save || input.value === currentValue) {
        td.textContent = currentValue;
        return;
      }
      try {
        const res = await apiPost("/api/cell", {
          entityId: String(entityId),
          predicate,
          value: input.value,
          reason: `Studio: Edit ${predicate} on #${entityId}`
        });
        toast(`Gespeichert in Commit ${res.commitId}`);
        await loadAll();
      } catch (err) {
        toast(err.message, "error");
        td.textContent = currentValue;
      }
    };

    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") finish(true);
      if (e.key === "Escape") finish(false);
    });
    input.addEventListener("blur", () => finish(true));
  };

  const selectRow = async (entityId) => {
    state.selectedEntityId = entityId;
    document.querySelectorAll("#datasheetBody tr").forEach((tr) => {
      const idCell = tr.firstElementChild?.textContent;
      tr.classList.toggle("selected", idCell === `#${entityId}`);
    });
    await loadEntityDetail(entityId, false);
  };

  const loadEntityDetail = async (entityId, silentOnMissing) => {
    try {
      const detail = await apiGet("/api/entity", { id: entityId });
      renderEntityDrawer(detail);
    } catch (err) {
      if (!silentOnMissing) toast(err.message, "error");
      document.getElementById("drawerEmpty").classList.remove("hidden");
      document.getElementById("drawerContent").classList.add("hidden");
    }
  };

  const renderEntityDrawer = (detail) => {
    document.getElementById("drawerEmpty").classList.add("hidden");
    const container = document.getElementById("drawerContent");
    container.classList.remove("hidden");
    container.innerHTML = "";

    container.appendChild(el("div", { className: "drawer-header" }, [
      el("div", { className: "pill", textContent: `${detail.typeName} · #${detail.id} · ${detail.kind}` }),
      el("h3", { textContent: detail.label })
    ]));

    // Attributes section (editable in place)
    const attrSec = el("div", { className: "drawer-section" }, el("h4", { textContent: "Attribute (Unterdatenblatt)" }));
    if (detail.attributes.length === 0 && detail.kind !== "VALUE") {
      attrSec.appendChild(el("div", { className: "muted", textContent: "Keine skalaren Attribute gesetzt." }));
    }
    detail.attributes.forEach((a) => {
      const inp = el("input", { value: a.value, disabled: detail.historical ? "true" : null });
      const saveBtn = el("button", {
        className: "btn btn-sm btn-secondary",
        textContent: "OK",
        disabled: detail.historical ? "true" : null,
        onClick: async () => {
          try {
            const res = await apiPost("/api/cell", {
              entityId: String(detail.id),
              predicate: a.predicate,
              value: inp.value,
              reason: `Studio: Update ${a.predicate} on #${detail.id}`
            });
            toast(`${a.predicate} → ${res.commitId}`);
            await loadAll();
          } catch (err) { toast(err.message, "error"); }
        }
      });
      attrSec.appendChild(el("div", { className: "attr-row" }, [
        el("div", { className: "attr-name", textContent: a.predicate }),
        el("div", { className: "attr-val" }, inp),
        saveBtn
      ]));
    });
    container.appendChild(attrSec);

    // Outgoing Relations
    const outSec = el("div", { className: "drawer-section" }, el("h4", { textContent: `Ausgehende Relationen (${detail.outgoingRelations.length})` }));
    detail.outgoingRelations.forEach((rel) => {
      outSec.appendChild(el("div", { className: "rel-card" }, [
        el("div", {}, [
          el("div", { className: "muted", style: "font-size:11px", textContent: `${rel.predicate} → ${rel.otherType} (#${rel.otherId})` }),
          el("button", {
            className: "rel-link",
            textContent: rel.otherLabel,
            onClick: () => jumpToEntity(rel.otherId)
          })
        ]),
        !detail.historical ? el("button", {
          className: "btn btn-sm btn-danger",
          textContent: "Trennen",
          onClick: async () => {
            try {
              const res = await apiPost("/api/disconnect", { edgeId: String(rel.edgeId) });
              toast(`Kante #${rel.edgeId} getrennt (${res.commitId})`);
              await loadAll();
            } catch (err) { toast(err.message, "error"); }
          }
        }) : null
      ]));
    });
    container.appendChild(outSec);

    // Connect Relation Quick Box
    if (!detail.historical && detail.kind === "OBJECT" && state.overview) {
      const relPreds = state.overview.predicates.filter((p) =>
        p.rangeKind !== "VALUE_TYPE" && (p.domainName === detail.typeName || p.domainName === "ANY_OBJECT" || p.domainName === "ANY_ENTITY")
      );
      if (relPreds.length > 0) {
        const predSel = el("select");
        relPreds.forEach((p) => predSel.appendChild(el("option", { value: p.name, textContent: `${p.name} → ${p.rangeName}` })));
        const targetSel = el("select");
        const targetManual = el("input", { placeholder: "Oder Ziel-ID z. B. 1025" });

        const refreshTargets = async () => {
          const chosen = relPreds.find((p) => p.name === predSel.value);
          targetSel.innerHTML = "";
          if (chosen && chosen.rangeKind === "OBJECT_TYPE") {
            const ds = await apiGet("/api/datasheet", { type: chosen.rangeName, limit: 150 });
            ds.rows.forEach((r) => targetSel.appendChild(el("option", { value: r._id, textContent: `#${r._id} ${r._label}` })));
          }
        };
        predSel.addEventListener("change", refreshTargets);
        refreshTargets();

        const box = el("div", { className: "drawer-section" }, [
          el("h4", { textContent: "+ Relation verknüpfen" }),
          el("div", { className: "connect-box" }, [
            predSel,
            targetSel,
            targetManual,
            el("button", {
              className: "btn btn-sm btn-primary",
              textContent: "Verbinden",
              onClick: async () => {
                const targetId = targetManual.value.trim() || targetSel.value;
                if (!targetId) { toast("Bitte Ziel-Entity wählen", "error"); return; }
                try {
                  const res = await apiPost("/api/connect", {
                    sourceId: String(detail.id),
                    predicate: predSel.value,
                    targetId
                  });
                  toast(`Verbunden in ${res.commitId}`);
                  await loadAll();
                } catch (err) { toast(err.message, "error"); }
              }
            })
          ])
        ]);
        container.appendChild(box);
      }
    }

    // Incoming Relations
    const inSec = el("div", { className: "drawer-section" }, el("h4", { textContent: `Eingehende Referenzen (${detail.incomingRelations.length})` }));
    if (detail.incomingRelations.length === 0) {
      inSec.appendChild(el("div", { className: "muted", textContent: "Keine eingehenden Referenzen." }));
    }
    detail.incomingRelations.forEach((rel) => {
      inSec.appendChild(el("div", { className: "rel-card" }, [
        el("div", {}, [
          el("div", { className: "muted", style: "font-size:11px", textContent: `← ${rel.predicate} von ${rel.otherType} (#${rel.otherId})` }),
          el("button", {
            className: "rel-link",
            textContent: rel.otherLabel,
            onClick: () => jumpToEntity(rel.otherId)
          })
        ])
      ]));
    });
    container.appendChild(inSec);

    // Entity Commit History
    const histSec = el("div", { className: "drawer-section" }, el("h4", { textContent: `Versionshistorie (${detail.history.length})` }));
    detail.history.forEach((h) => {
      histSec.appendChild(el("div", { className: "rel-card" }, [
        el("div", {}, [
          el("strong", { className: "mono", textContent: h.commitId }),
          document.createTextNode(` · ${h.actor} — ${h.reason}`)
        ]),
        el("button", {
          className: "btn btn-sm btn-secondary",
          textContent: "asOf",
          onClick: () => setTimeTravel(h.commitId)
        })
      ]));
    });
    container.appendChild(histSec);
  };

  const jumpToEntity = async (entityId) => {
    state.selectedEntityId = entityId;
    await loadEntityDetail(entityId, false);
  };

  const renderSchemaView = () => {
    if (!state.overview) return;
    const wrap = document.getElementById("schemaDiagram");
    wrap.innerHTML = "";

    const objTypes = state.overview.objectTypes;
    const relPreds = state.overview.predicates.filter((p) => p.rangeKind === "OBJECT_TYPE");
    const attrPreds = state.overview.predicates.filter((p) => p.rangeKind === "VALUE_TYPE");

    // Visual Access-style relationship cards with clickable table jump
    const grid = el("div", { style: "display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;" });
    objTypes.forEach((t) => {
      const attrs = attrPreds.filter((p) => p.domainName === t.name || p.domainName === "ANY_OBJECT");
      const rels = relPreds.filter((p) => p.domainName === t.name || p.domainName === "ANY_OBJECT");

      const card = el("div", { className: "card", style: "padding:10px;border-top:3px solid var(--accent);" }, [
        el("div", { style: "display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;" }, [
          el("button", { className: "rel-link", textContent: t.name, onClick: () => selectType(t.name) }),
          el("span", { className: "badge", textContent: String(t.count) })
        ]),
        el("div", { style: "font-size:11px;margin-bottom:6px;" },
          attrs.slice(0, 7).map((a) => el("div", { className: "muted", textContent: `• ${a.name}: ${a.rangeName}` }))
        ),
        rels.length > 0 ? el("div", { style: "border-top:1px solid #e7ecf1;padding-top:5px;font-size:11px;" },
          rels.map((r) => el("div", { className: "mono", style: "color:var(--accent)", textContent: `→ ${r.name} → ${r.rangeName} (${r.count})` }))
        ) : null
      ]);
      grid.appendChild(card);
    });
    wrap.appendChild(grid);
  };

  const renderSchemaSelectors = () => {
    if (!state.overview) return;
    const ov = state.overview;

    const domainSel = document.getElementById("predDomainSelect");
    const rangeSel = document.getElementById("predRangeSelect");
    domainSel.innerHTML = "";
    rangeSel.innerHTML = "";

    ["ANY_OBJECT", "ANY_EDGE", "ANY_ENTITY"].forEach((w) => domainSel.appendChild(el("option", { value: w, textContent: w })));
    ov.objectTypes.forEach((t) => domainSel.appendChild(el("option", { value: t.name, textContent: t.name })));

    ov.valueTypes.forEach((v) => rangeSel.appendChild(el("option", { value: v.name, textContent: `${v.name} (${v.scalarType})` })));
    ov.objectTypes.forEach((t) => rangeSel.appendChild(el("option", { value: t.name, textContent: `${t.name} (Objekt)` })));
    ["ANY_OBJECT", "STRING", "INT", "BOOLEAN"].forEach((w) => rangeSel.appendChild(el("option", { value: w, textContent: w })));

    // Query Builder dropdowns
    const qbMatch = document.getElementById("qbMatchType");
    const qbPred = document.getElementById("qbJoinPred");
    const qbTarget = document.getElementById("qbJoinTarget");
    qbMatch.innerHTML = "";
    ov.objectTypes.forEach((t) => qbMatch.appendChild(el("option", { value: t.name, textContent: t.name })));

    qbPred.innerHTML = '<option value="">-- Kein Join --</option>';
    ov.predicates.filter((p) => p.rangeKind === "OBJECT_TYPE").forEach((p) => {
      qbPred.appendChild(el("option", { value: p.name, textContent: `${p.name} (${p.domainName} → ${p.rangeName})` }));
    });

    qbTarget.innerHTML = '<option value="">-- Zieltyp optional --</option>';
    ov.objectTypes.forEach((t) => qbTarget.appendChild(el("option", { value: t.name, textContent: t.name })));
  };

  const renderQueryPresets = () => {
    const presets = [
      {
        label: "ADRs ↔ Invariante I-17",
        dsl: 'MATCH (adr:ArchitectureDecision)\nJOIN (adr)-[addressesInvariant]->(inv:Invariant)\nWHERE inv.id = "I-17"\nSELECT adr.id, adr.title, inv.id, inv.title'
      },
      {
        label: "Modul-Abhängigkeiten",
        dsl: "MATCH (m:Module)\nJOIN (m)-[dependsOnModule]->(dep:Module)\nSELECT m.id, dep.id, dep.category\nORDER BY m.id ASC"
      },
      {
        label: "Module pro Stufe",
        dsl: "MATCH (m:Module)\nJOIN (m)-[implementsStage]->(s:Stage)\nSELECT m.id, s.id, s.title, s.status\nORDER BY s.stageNumber ASC"
      },
      {
        label: "WAL-Invarianten",
        dsl: 'MATCH (i:Invariant)\nWHERE i.summary CONTAINS "WAL"\nSELECT i.id, i.title, i.category'
      }
    ];
    const row = document.getElementById("queryPresets");
    row.innerHTML = "";
    presets.forEach((p) => {
      row.appendChild(el("button", {
        className: "btn btn-sm btn-secondary",
        type: "button",
        textContent: p.label,
        onClick: () => {
          document.getElementById("dslTextarea").value = p.dsl;
          runDslQuery();
        }
      }));
    });
  };

  const buildDslFromUi = () => {
    const type = document.getElementById("qbMatchType").value || "Invariant";
    const joinPred = document.getElementById("qbJoinPred").value;
    const joinTarget = document.getElementById("qbJoinTarget").value;
    const whereField = document.getElementById("qbWhereField").value.trim();
    const whereOp = document.getElementById("qbWhereOp").value;
    const whereVal = document.getElementById("qbWhereVal").value.trim();
    const select = document.getElementById("qbSelect").value.trim();
    const limit = document.getElementById("qbLimit").value.trim();

    const lines = [`MATCH (a:${type})`];
    if (joinPred) {
      lines.push(joinTarget ? `JOIN (a)-[${joinPred}]->(b:${joinTarget})` : `JOIN (a)-[${joinPred}]->(b)`);
    }
    if (whereField && whereVal) {
      const quoted = (/^-?\d+(\.\d+)?$/.test(whereVal) || whereVal === "true" || whereVal === "false" || whereVal.startsWith('"'))
        ? whereVal
        : `"${whereVal.replace(/"/g, '\\"')}"`;
      lines.push(`WHERE ${whereField} ${whereOp} ${quoted}`);
    }
    if (select) lines.push(`SELECT ${select}`);
    if (limit) lines.push(`LIMIT ${limit}`);
    document.getElementById("dslTextarea").value = lines.join("\n");
  };

  const runDslQuery = async () => {
    const dsl = document.getElementById("dslTextarea").value.trim();
    if (!dsl) return;
    try {
      const res = await apiPost("/api/query", { dsl });
      document.getElementById("queryResultStats").textContent =
        `${res.rowCount} Treffer auf ${res.branch} (${res.commitId})`;
      const tbl = document.getElementById("queryResultTable");
      tbl.innerHTML = "";
      if (res.columns.length === 0) {
        tbl.appendChild(el("tr", {}, el("td", { className: "muted", textContent: "Keine Ergebnisse." })));
        return;
      }
      const thead = el("thead", {}, el("tr", {}, res.columns.map((c) => el("th", { textContent: c.key }))));
      const tbody = el("tbody");
      res.rows.forEach((r) => {
        const tr = el("tr");
        res.columns.forEach((c) => {
          if (c.kind === "ENTITY_BINDING") {
            const b = r.bindings[c.key];
            if (b) {
              tr.appendChild(el("td", {}, el("button", {
                className: "rel-link",
                textContent: `#${b.id} ${b.label}`,
                onClick: async () => {
                  state.selectedEntityId = b.id;
                  switchView("datasheet");
                  await loadEntityDetail(b.id, false);
                }
              })));
            } else {
              tr.appendChild(el("td", { textContent: "" }));
            }
          } else {
            tr.appendChild(el("td", { textContent: r.values[c.key] ?? "" }));
          }
        });
        tbody.appendChild(tr);
      });
      tbl.appendChild(thead);
      tbl.appendChild(tbody);
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const loadHistory = async () => {
    state.history = await apiGet("/api/history");
    renderHistoryView();
  };

  const renderHistoryView = () => {
    if (!state.history) return;
    const commits = state.history.commits;
    const tbody = document.getElementById("historyBody");
    tbody.innerHTML = "";
    [...commits].reverse().forEach((c) => {
      tbody.appendChild(el("tr", {}, [
        el("td", { className: "cell-id", textContent: c.id }),
        el("td", { textContent: c.branchName }),
        el("td", { textContent: c.dataCategory }),
        el("td", { textContent: c.actor }),
        el("td", { textContent: c.reason }),
        el("td", { className: "mono", textContent: c.timestamp.slice(0, 19) }),
        el("td", { textContent: String(c.changeCount) }),
        el("td", {}, el("button", {
          className: "btn btn-sm btn-secondary",
          textContent: "Zeitreise (asOf)",
          onClick: () => setTimeTravel(c.id)
        }))
      ]));
    });

    const fromSel = document.getElementById("diffFromSelect");
    const toSel = document.getElementById("diffToSelect");
    fromSel.innerHTML = "";
    toSel.innerHTML = "";
    commits.forEach((c, idx) => {
      fromSel.appendChild(el("option", { value: c.id, textContent: `${c.id} (${c.reason.slice(0, 32)})` }));
      toSel.appendChild(el("option", { value: c.id, textContent: `${c.id} (${c.reason.slice(0, 32)})` }));
      if (idx === Math.max(0, commits.length - 2)) fromSel.value = c.id;
      if (idx === commits.length - 1) toSel.value = c.id;
    });
  };

  const runDiff = async () => {
    const from = document.getElementById("diffFromSelect").value;
    const to = document.getElementById("diffToSelect").value;
    if (!from || !to) return;
    try {
      const d = await apiGet("/api/diff", { from, to });
      const out = document.getElementById("diffOutput");
      out.innerHTML = "";
      if (d.empty) {
        out.textContent = `Keine strukturellen Unterschiede zwischen ${d.fromCommitId} und ${d.toCommitId}.`;
        return;
      }
      const addSection = (title, items, formatter) => {
        if (items.length === 0) return;
        const g = el("div", { className: "diff-group" }, [
          el("strong", { textContent: `${title} (${items.length})` }),
          el("ul", { style: "margin:4px 0 8px 18px;" }, items.slice(0, 40).map((it) => el("li", { textContent: formatter(it) })))
        ]);
        out.appendChild(g);
      };
      addSection("+ Hinzugefügte Entities", d.addedEntities, (e) => `#${e.id} [${e.typeName}] ${e.label}`);
      addSection("- Entfernte Entities", d.removedEntities, (e) => `#${e.id} [${e.typeName}] ${e.label}`);
      addSection("~ Aktualisierte Werte", d.updatedValues, (u) => `#${u.entityId}: "${u.previousValue}" → "${u.newValue}"`);
      addSection("+ Verbundene Kanten", d.addedEdges, (e) => `Edge #${e.id}: #${e.sourceId} -[${e.predicate}]-> #${e.targetId}`);
      addSection("- Getrennte Kanten", d.removedEdges, (e) => `Edge #${e.id}: #${e.sourceId} -[${e.predicate}]-> #${e.targetId}`);
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const exportVegPatch = async () => {
    const from = document.getElementById("diffFromSelect").value;
    const to = document.getElementById("diffToSelect").value;
    if (!from || !to) return;
    try {
      const res = await apiGet("/api/patch", { from, to });
      document.getElementById("patchTextarea").value = res.patchText;
      toast(`.vegpatch (${from} → ${to}) exportiert`);
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const submitVegPatch = async (mode) => {
    const patchText = document.getElementById("patchTextarea").value.trim();
    if (!patchText) {
      toast("Bitte zuerst einen .vegpatch-Text einfügen oder exportieren.", "error");
      return;
    }
    const out = document.getElementById("patchResultOutput");
    try {
      const res = await apiPost("/api/patch", { patchText, mode });
      out.innerHTML = "";
      if (res.alreadyApplied) {
        out.textContent = `Idempotent: Patch ${res.patchId} war bereits auf ${res.targetBranch} enthalten (${res.commitId}).`;
        toast("Patch bereits enthalten");
      } else if (res.merged) {
        out.textContent = `Erfolgreich (${res.mode}) in Branch '${res.targetBranch}' übernommen → Commit ${res.commitId} (Patch ${res.patchId}).`;
        toast(`Patch gemerged: ${res.commitId}`);
        await loadAll();
      } else {
        out.appendChild(el("strong", { style: "color:var(--danger);", textContent: `Merge-Konflikte (${res.conflicts.length}) — Zielbranch unverändert:` }));
        out.appendChild(el("ul", { style: "margin:4px 0 0 18px;" },
          res.conflicts.map((c) => el("li", { textContent: `[${c.type}] Entity #${c.entityId}: ${c.description}` }))));
        toast("Merge-Konflikt erkannt!", "error");
      }
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const setTimeTravel = (commitId) => {
    state.asOf = commitId;
    loadAll();
  };

  const openNewRecordDialog = () => {
    if (!state.datasheet || state.datasheet.typeKind === "VALUE_TYPE") {
      toast("Bitte zuerst einen Objekttyp im Objektkatalog auswählen.", "error");
      return;
    }
    const dlg = document.getElementById("newRecordDialog");
    document.getElementById("newRecordDialogTitle").textContent = `Neuer Datensatz: ${state.datasheet.typeName}`;
    const fieldsDiv = document.getElementById("newRecordFields");
    fieldsDiv.innerHTML = "";
    const attrCols = state.datasheet.columns.filter((c) => c.kind === "ATTRIBUTE");
    if (attrCols.length === 0) {
      fieldsDiv.appendChild(el("div", { className: "muted", textContent: "Dieser Objekttyp besitzt aktuell nur Objekt-IDs." }));
    }
    attrCols.forEach((col) => {
      fieldsDiv.appendChild(el("label", {}, [
        el("span", { textContent: `${col.name} (${col.scalarType || "STRING"})` }),
        el("input", { name: col.name, placeholder: col.scalarType === "INT" ? "Ganzzahl..." : "Wert eingeben..." })
      ]));
    });
    dlg.showModal();
  };

  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll(".tab-btn").forEach((btn) => {
      btn.addEventListener("click", () => switchView(btn.dataset.view));
    });
    document.getElementById("refreshBtn").addEventListener("click", loadAll);
    document.getElementById("branchSelect").addEventListener("change", (e) => {
      state.branch = e.target.value;
      state.asOf = "";
      loadAll();
    });
    document.getElementById("asOfSelect").addEventListener("change", (e) => {
      state.asOf = e.target.value;
      loadAll();
    });
    document.getElementById("exitTimeTravelBtn").addEventListener("click", () => {
      state.asOf = "";
      loadAll();
    });
    document.getElementById("catalogFilterInput").addEventListener("input", (e) => {
      state.catalogFilter = e.target.value;
      renderCatalog();
    });
    document.getElementById("globalSearchInput").addEventListener("input", debounce((e) => {
      state.globalFilter = e.target.value;
      state.offset = 0;
      loadDatasheet();
    }, 220));
    document.getElementById("clearFiltersBtn").addEventListener("click", () => {
      state.colFilters = {};
      state.globalFilter = "";
      document.getElementById("globalSearchInput").value = "";
      state.offset = 0;
      loadDatasheet();
    });

    document.getElementById("newRecordBtn").addEventListener("click", openNewRecordDialog);
    document.getElementById("cancelNewRecordBtn").addEventListener("click", () => {
      document.getElementById("newRecordDialog").close();
    });
    document.getElementById("newRecordForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const formData = new FormData(e.target);
      const attributes = {};
      for (const [k, v] of formData.entries()) {
        if (String(v).trim() !== "") attributes[k] = String(v);
      }
      try {
        const res = await apiPost("/api/objects", { typeName: state.activeType, attributes });
        document.getElementById("newRecordDialog").close();
        state.selectedEntityId = res.entityId;
        toast(`Objekt #${res.entityId} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });

    document.getElementById("createObjectTypeForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      try {
        const res = await apiPost("/api/schema/object-type", { name: fd.get("name") });
        e.target.reset();
        toast(`Objekttyp ${res.name} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });
    document.getElementById("createValueTypeForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      try {
        const res = await apiPost("/api/schema/value-type", {
          name: fd.get("name"),
          scalarType: fd.get("scalarType"),
          uniqueValues: fd.get("uniqueValues") === "on"
        });
        e.target.reset();
        toast(`Werttyp ${res.name} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });
    document.getElementById("createPredicateForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      const maxOut = String(fd.get("maxOutgoing") || "").trim();
      const maxIn = String(fd.get("maxIncoming") || "").trim();
      try {
        const res = await apiPost("/api/schema/predicate", {
          name: fd.get("name"),
          domain: fd.get("domain"),
          range: fd.get("range"),
          maxOutgoing: maxOut ? Number(maxOut) : null,
          maxIncoming: maxIn ? Number(maxIn) : null,
          uniqueTriple: fd.get("uniqueTriple") === "on"
        });
        e.target.reset();
        toast(`Prädikat ${res.name} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });

    document.getElementById("applyBuilderBtn").addEventListener("click", buildDslFromUi);
    document.getElementById("runQueryBtn").addEventListener("click", runDslQuery);
    document.getElementById("dslTextarea").addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); runDslQuery(); }
    });
    document.getElementById("runDiffBtn").addEventListener("click", runDiff);
    document.getElementById("exportPatchBtn").addEventListener("click", exportVegPatch);
    document.getElementById("mergePatchBtn").addEventListener("click", () => submitVegPatch("MERGE"));
    document.getElementById("applyPatchBtn").addEventListener("click", () => submitVegPatch("APPLY"));

    loadAll();
  });
})();
